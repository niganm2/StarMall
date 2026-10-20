package com.small.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.api.client.CartClient;
import com.small.api.client.ItemClient;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.OrderDetailDTO;
import com.small.common.exception.BadRequestException;
import com.small.common.utils.UserContext;
import com.small.trade.constants.MQConstants;
import com.small.trade.domain.dto.OrderFormDTO;
import com.small.trade.domain.po.Order;
import com.small.trade.domain.po.OrderDetail;
import com.small.trade.mapper.OrderMapper;
import com.small.trade.service.IOrderDetailService;
import com.small.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @GlobalTransactional  // 开启全局事务XA模式
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);

        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.清理购物车商品
        cartClient.deleteCartItemByIds(itemIds);

        // 4.扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }

        // 5.发送延迟消息，检测订单支付状态
        rabbitTemplate.convertAndSend(
                MQConstants.DELAY_EXCHANGE_NAME,
                MQConstants.DELAY_ORDER_KEY,
                order.getId(), message -> {
                    // 延迟10秒
                    message.getMessageProperties().setDelay(10000);
                    return message;
                });
        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    @Override
    @GlobalTransactional
    public void cancelOrder(Long orderId) {
        // 1. 根据订单ID查询订单信息
        Order order = getById(orderId);
        // 2. 判断订单是否存在
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 3. 判断订单状态是否允许取消（只有status=1 未付款状态才能取消）
        // status: 1-未付款 2-已付款 3-已发货 4-交易成功 5-交易取消 6-已评价
        if (order.getStatus() != 1) {
            throw new BadRequestException("订单状态不允许取消");
        }

        // 4. 查询订单详情（包含购买的商品列表和数量）
        List<OrderDetail> details = detailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();

        // 5. 将订单详情转换为OrderDetailDTO列表（用于调用item-service恢复库存）
        List<OrderDetailDTO> detailDTOS = details.stream()
                .map(d -> {
                    OrderDetailDTO dto = new OrderDetailDTO();
                    dto.setItemId(d.getItemId());  // 设置商品ID
                    dto.setNum(d.getNum());          // 设置购买数量
                    return dto;
                })
                .collect(Collectors.toList());

        // 6. 更新订单状态为"交易取消"（status=5），并记录关闭时间
        Order updateOrder = new Order();
        updateOrder.setId(orderId);
        updateOrder.setStatus(5);                    // status=5 表示交易取消
        updateOrder.setCloseTime(LocalDateTime.now()); // 设置订单关闭时间
        updateById(updateOrder);

        // 7. 调用item-service恢复商品库存（将之前扣减的库存加回去）
        itemClient.restoreStock(detailDTOS);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
