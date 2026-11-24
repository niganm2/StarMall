package com.small.trade.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.api.client.CartClient;
import com.small.api.client.ItemClient;
import com.small.api.client.PayClient;
import com.small.api.client.UserClient;
import com.small.api.dto.AddressDTO;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.OrderDetailDTO;
import com.small.common.domain.PageDTO;
import com.small.common.exception.BadRequestException;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.common.utils.UserContext;
import com.small.trade.constants.MQConstants;
import com.small.trade.domain.dto.OrderFormDTO;
import com.small.trade.domain.po.Order;
import com.small.trade.domain.po.OrderDetail;
import com.small.trade.domain.vo.OrderDetailVO;
import com.small.trade.domain.vo.OrderListItemVO;
import com.small.trade.domain.vo.OrderVO;
import com.small.trade.limit.OrderRateLimiter;
import com.small.trade.mapper.OrderMapper;
import com.small.trade.service.IOrderDetailService;
import com.small.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final PayClient payClient;
    private final UserClient userClient;
    private final RabbitTemplate rabbitTemplate;
    private final OrderRateLimiter orderRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @GlobalTransactional  // 开启全局事务（Seata AT 模式：一阶段本地提交，二阶段基于 undo_log 回滚）
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 0.按用户维度做滑动窗口频控，防止脚本刷单与重复提交
        orderRateLimiter.check(UserContext.getUser());

        // 0.5.校验收货地址：必须存在且属于当前登录用户
        if (orderFormDTO.getAddressId() == null) {
            throw new BadRequestException("请选择收货地址");
        }
        AddressDTO address;
        try {
            address = userClient.queryAddressById(orderFormDTO.getAddressId());
        } catch (Throwable e) {
            // Sentinel 会把 Feign 降级抛出的异常包装成 AssertionError，这里按消息内容还原业务提示
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("收货地址不存在或不属于")) {
                throw new BadRequestException("收货地址不存在或不属于当前登录用户");
            }
            throw new BadRequestException("收货地址校验失败，请稍后重试");
        }
        if (address == null) {
            throw new BadRequestException("收货地址不存在");
        }

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
        // 空列表说明商品服务已降级（Feign fallback 返回空），不能误报为"商品不存在"
        if (items == null || items.isEmpty()) {
            throw new BizIllegalException("商品服务繁忙，请稍后重试！");
        }
        if (items.size() < itemIds.size()) {
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
        // 1.6.幂等控制：同一用户相同收货地址、支付方式、商品明细的重复提交，30 秒内只受理一次
        String idemKey = "order:idem:" + order.getUserId() + ":" + buildSubmitHash(orderFormDTO);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(idemKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(acquired)) {
            throw new BadRequestException("请勿重复提交订单，请稍后重试");
        }
        try {
            // 1.7.将Order写入数据库order表中
            save(order);

            // 2.保存订单详情
            List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
            detailService.saveBatch(details);

            // 3.清理购物车商品
            cartClient.deleteCartItemByIds(itemIds);

            // 4.扣减库存
            try {
                itemClient.deductStock(detailDTOS);
            } catch (Throwable e) {
                // Feign 降级后异常会被 Sentinel 包装成 AssertionError（Error 而非 Exception），故捕获 Throwable
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("库存不足")) {
                    throw new BizIllegalException("库存不足，请减少购买数量后重试！");
                }
                throw new BizIllegalException("库存服务繁忙，请稍后重试！");
            }

            // 5.发送延迟消息，检测订单支付状态
            rabbitTemplate.convertAndSend(
                    MQConstants.DELAY_EXCHANGE_NAME,
                    MQConstants.DELAY_ORDER_KEY,
                    order.getId(), message -> {
                        // 延迟15分钟，超时未支付才取消订单
                        message.getMessageProperties().setDelay(15 * 60 * 1000);
                        return message;
                    });
            return order.getId();
        } catch (RuntimeException e) {
            // 下单失败释放幂等锁，避免用户修正后 30 秒内无法重试
            stringRedisTemplate.delete(idemKey);
            throw e;
        }
    }

    /**
     * 生成下单幂等键摘要：收货地址 + 支付方式 + 商品明细（按商品 id 排序后取 MD5）
     */
    private String buildSubmitHash(OrderFormDTO orderFormDTO) {
        String details = orderFormDTO.getDetails().stream()
                .sorted(Comparator.comparing(OrderDetailDTO::getItemId))
                .map(d -> d.getItemId() + ":" + d.getNum())
                .collect(Collectors.joining(","));
        String raw = orderFormDTO.getAddressId() + "|" + orderFormDTO.getPaymentType() + "|" + details;
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        // 仅允许从「未付款(1)」变更为「已付款(2)」，避免支付成功消息重投把已退款订单改回已付款
        lambdaUpdate()
                .set(Order::getStatus, 2)
                .set(Order::getPayTime, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, 1)
                .update();
    }

    @Override
    public OrderVO queryOrderById(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 只能查看自己的订单，防止越权
        if (!order.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("无权查看该订单");
        }
        return BeanUtils.copyBean(order, OrderVO.class);
    }

    @Override
    public void deleteOrder(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 只能删除自己的订单，防止越权
        if (!order.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("无权操作该订单");
        }
        // 只有已取消、已退款的订单允许删除，避免未付款订单被删除后库存无法恢复
        if (order.getStatus() != 5 && order.getStatus() != 7) {
            throw new BadRequestException("只有已取消或已退款的订单才能删除");
        }
        removeById(orderId);
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
        // 2.1 只能取消自己的订单，防止越权
        if (!order.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("无权操作该订单");
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

    @Override
    public void shipOrder(Long orderId) {
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 2.只有订单所属用户可以操作（demo 中模拟商家发货）
        if (!order.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("无权操作该订单");
        }
        // 3.只有已付款未发货的订单才能发货
        if (order.getStatus() != 2) {
            throw new BadRequestException("只有已付款待发货的订单才能发货");
        }
        // 4.更新状态为已发货，并记录发货时间
        Order updateOrder = new Order();
        updateOrder.setId(orderId);
        updateOrder.setStatus(3);
        updateOrder.setConsignTime(LocalDateTime.now());
        updateById(updateOrder);
    }

    @Override
    @GlobalTransactional
    public void refundOrder(Long orderId, String reason) {
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        if (!order.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("无权操作该订单");
        }
        // 2.已付款且未收货的订单（待发货、已发货待收货）都可以申请退款
        if (order.getStatus() != 2 && order.getStatus() != 3) {
            throw new BadRequestException("当前订单状态不支持退款");
        }
        // 3.调用支付服务原路退款（余额退回用户余额）
        log.info("订单申请退款，orderId={}, 原订单状态={}, 退款原因={}", orderId, order.getStatus(), reason);
        payClient.refundPayOrder(orderId);

        // 4.恢复商品库存
        List<OrderDetail> details = detailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();
        List<OrderDetailDTO> detailDTOS = details.stream()
                .map(d -> {
                    OrderDetailDTO dto = new OrderDetailDTO();
                    dto.setItemId(d.getItemId());
                    dto.setNum(d.getNum());
                    return dto;
                })
                .collect(Collectors.toList());
        itemClient.restoreStock(detailDTOS);

        // 5.更新订单状态为已退款（7），并记录关闭时间
        Order updateOrder = new Order();
        updateOrder.setId(orderId);
        updateOrder.setStatus(7);
        updateOrder.setCloseTime(LocalDateTime.now());
        updateById(updateOrder);
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

    @Override
    public PageDTO<OrderListItemVO> queryMyOrders(Integer pageNo, Integer pageSize, Integer status) {
        Page<Order> page = lambdaQuery()
                .eq(Order::getUserId, UserContext.getUser())
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getCreateTime)
                .page(new Page<>(pageNo, pageSize));
        if (page.getRecords().isEmpty()) {
            return PageDTO.empty(page);
        }
        List<OrderListItemVO> list = page.getRecords().stream().map(order -> {
            OrderListItemVO vo = BeanUtils.copyBean(order, OrderListItemVO.class);
            List<OrderDetail> details = detailService.lambdaQuery()
                    .eq(OrderDetail::getOrderId, order.getId())
                    .list();
            vo.setDetails(BeanUtils.copyList(details, OrderDetailVO.class));
            return vo;
        }).collect(Collectors.toList());
        return PageDTO.of(page, list);
    }

    @Override
    public Long queryMyOrderCount(Integer status) {
        return lambdaQuery()
                .eq(Order::getUserId, UserContext.getUser())
                .eq(status != null, Order::getStatus, status)
                .count();
    }
}
