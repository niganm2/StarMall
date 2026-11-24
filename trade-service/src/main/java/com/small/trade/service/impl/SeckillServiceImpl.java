package com.small.trade.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.small.api.client.ItemClient;
import com.small.api.client.UserClient;
import com.small.api.dto.AddressDTO;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.OrderDetailDTO;
import com.small.common.exception.BadRequestException;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.UserContext;
import com.small.trade.config.SeckillProperties;
import com.small.trade.constants.MQConstants;
import com.small.trade.domain.dto.SeckillOrderMessage;
import com.small.trade.domain.po.Order;
import com.small.trade.domain.po.OrderDetail;
import com.small.trade.domain.vo.SeckillItemVO;
import com.small.trade.domain.vo.SeckillResultVO;
import com.small.trade.service.IOrderDetailService;
import com.small.trade.service.IOrderService;
import com.small.trade.service.ISeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀实现：Redis Lua 原子预减库存 + 一人一单，MQ 异步落单，避免同 SKU 的 Seata 全局锁冲突。
 *
 * @author YL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements ISeckillService {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USERS_KEY = "seckill:users:";
    private static final String RESULT_KEY = "seckill:result:";
    private static final String ORDER_KEY = "seckill:order:";

    private static final String RESULT_QUEUING = "QUEUING";
    private static final String RESULT_SUCCESS = "SUCCESS:";
    private static final String RESULT_FAIL = "FAIL:";

    private static final String PENDING = "PENDING";
    /** 限购记录与幂等标记保留 24 小时 */
    private static final long USER_TTL_SECONDS = 24 * 60 * 60L;

    /**
     * 原子预减：KEYS=[库存, 限购用户集合]，ARGV=[userId]。
     * 返回 1-成功，0-已售罄，-1-重复抢购，-2-库存未初始化。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>(
            "local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')\n" +
            "if stock < 0 then return -2 end\n" +
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end\n" +
            "if stock <= 0 then return 0 end\n" +
            "redis.call('DECR', KEYS[1])\n" +
            "redis.call('SADD', KEYS[2], ARGV[1])\n" +
            "redis.call('EXPIRE', KEYS[2], " + USER_TTL_SECONDS + ")\n" +
            "return 1\n",
            Long.class);

    private final SeckillProperties seckillProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ItemClient itemClient;
    private final UserClient userClient;
    private final IOrderService orderService;
    private final IOrderDetailService detailService;

    @Override
    public List<SeckillItemVO> queryActivities() {
        List<SeckillProperties.Activity> activities = seckillProperties.getActivities();
        if (activities == null || activities.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> itemIds = activities.stream()
                .map(SeckillProperties.Activity::getItemId)
                .collect(Collectors.toList());
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.isEmpty()) {
            throw new BizIllegalException("商品服务繁忙，请稍后重试！");
        }
        Map<Long, ItemDTO> itemMap = items.stream()
                .collect(Collectors.toMap(ItemDTO::getId, item -> item, (a, b) -> a));
        List<SeckillItemVO> result = new ArrayList<>(activities.size());
        for (SeckillProperties.Activity activity : activities) {
            ItemDTO item = itemMap.get(activity.getItemId());
            if (item == null) {
                continue;
            }
            int totalStock = initStock(activity, item.getStock());
            int remain = currentStock(activity.getItemId());
            SeckillItemVO vo = new SeckillItemVO();
            vo.setItemId(item.getId());
            vo.setName(item.getName());
            vo.setImage(item.getImage());
            vo.setOriginalPrice(item.getPrice());
            vo.setSeckillPrice(activity.getSeckillPrice());
            vo.setStock(remain);
            vo.setTotalStock(totalStock);
            vo.setSoldOut(remain <= 0);
            result.add(vo);
        }
        return result;
    }

    @Override
    public String seckill(Long itemId, Long addressId) {
        Long userId = UserContext.getUser();
        SeckillProperties.Activity activity = findActivity(itemId);
        if (activity == null) {
            throw new BadRequestException("秒杀活动不存在或已结束");
        }
        if (addressId == null) {
            throw new BadRequestException("请选择收货地址");
        }
        validateAddress(addressId);

        Long allowed;
        try {
            ensureStockInitialized(activity);
            allowed = stringRedisTemplate.execute(SECKILL_SCRIPT,
                    Arrays.asList(STOCK_KEY + itemId, USERS_KEY + itemId),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.error("秒杀预减库存失败, userId={}, itemId={}", userId, itemId, e);
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }
        if (allowed == null) {
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }
        if (allowed == 0L) {
            throw new BizIllegalException("商品已抢完，下次再来~");
        }
        if (allowed == -1L) {
            throw new BadRequestException("每人限购一件，您已参与过本次秒杀");
        }
        if (allowed == -2L) {
            // 库存 key 意外缺失，重新装载后提示重试
            initStockFromDb(activity);
            log.warn("秒杀库存未初始化，已重新装载, itemId={}", itemId);
            throw new BizIllegalException("活动太火爆了，请稍后再试！");
        }

        String resultKey = RESULT_KEY + itemId + ":" + userId;
        try {
            stringRedisTemplate.opsForValue().set(resultKey, RESULT_QUEUING,
                    seckillProperties.getResultTtlMinutes(), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("写入秒杀排队状态失败, userId={}, itemId={}", userId, itemId, e);
            rollbackReservation(itemId, userId);
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }

        try {
            SeckillOrderMessage message = new SeckillOrderMessage(
                    userId, itemId, addressId, 3, activity.getSeckillPrice());
            rabbitTemplate.convertAndSend(MQConstants.SECKILL_EXCHANGE_NAME,
                    MQConstants.SECKILL_ORDER_KEY,
                    objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.error("秒杀消息发送失败，回补 Redis 库存, userId={}, itemId={}", userId, itemId, e);
            rollbackReservation(itemId, userId);
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }
        return RESULT_QUEUING;
    }

    @Override
    public SeckillResultVO queryResult(Long itemId) {
        Long userId = UserContext.getUser();
        SeckillResultVO vo = new SeckillResultVO();
        String value;
        try {
            value = stringRedisTemplate.opsForValue().get(RESULT_KEY + itemId + ":" + userId);
        } catch (Exception e) {
            log.error("查询秒杀结果失败, userId={}, itemId={}", userId, itemId, e);
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }
        if (value == null) {
            vo.setStatus("NONE");
            return vo;
        }
        if (value.startsWith(RESULT_SUCCESS)) {
            vo.setStatus("SUCCESS");
            vo.setOrderId(Long.valueOf(value.substring(RESULT_SUCCESS.length())));
        } else if (value.startsWith(RESULT_FAIL)) {
            vo.setStatus("FAIL");
            vo.setMsg(value.substring(RESULT_FAIL.length()));
        } else {
            vo.setStatus("QUEUING");
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSeckillOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long itemId = message.getItemId();
        String orderKey = ORDER_KEY + itemId + ":" + userId;

        // 幂等：消息重复投递时直接复用已创建的订单
        String existed = stringRedisTemplate.opsForValue().get(orderKey);
        if (existed != null && !PENDING.equals(existed)) {
            return Long.valueOf(existed);
        }
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(orderKey, PENDING, USER_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            String current = stringRedisTemplate.opsForValue().get(orderKey);
            if (current != null && !PENDING.equals(current)) {
                return Long.valueOf(current);
            }
            throw new BizIllegalException("该秒杀订单正在处理中，请勿重复提交");
        }

        List<ItemDTO> items = itemClient.queryItemByIds(Collections.singletonList(itemId));
        if (items == null || items.isEmpty()) {
            throw new BizIllegalException("商品服务繁忙，秒杀失败");
        }
        ItemDTO item = items.get(0);

        Integer price = message.getPrice();
        if (price == null) {
            SeckillProperties.Activity activity = findActivity(itemId);
            price = activity == null ? item.getPrice() : activity.getSeckillPrice();
        }

        Order order = new Order();
        order.setTotalFee(price);
        order.setPaymentType(message.getPaymentType() == null ? 3 : message.getPaymentType());
        order.setUserId(userId);
        order.setStatus(1);
        orderService.save(order);

        OrderDetail detail = new OrderDetail();
        detail.setOrderId(order.getId());
        detail.setItemId(itemId);
        detail.setNum(1);
        detail.setName(item.getName());
        detail.setSpec(item.getSpec());
        detail.setPrice(price);
        detail.setImage(item.getImage());
        detailService.save(detail);

        try {
            OrderDetailDTO deduct = new OrderDetailDTO().setItemId(itemId).setNum(1);
            itemClient.deductStock(Collections.singletonList(deduct));
        } catch (Throwable e) {
            log.error("秒杀扣减 DB 库存失败, userId={}, itemId={}", userId, itemId, e);
            throw new BizIllegalException("库存不足，秒杀失败");
        }

        // 复用支付超时检测：15 分钟未支付自动取消并恢复 DB 库存
        rabbitTemplate.convertAndSend(MQConstants.DELAY_EXCHANGE_NAME, MQConstants.DELAY_ORDER_KEY,
                order.getId(), msg -> {
                    msg.getMessageProperties().setDelay(15 * 60 * 1000);
                    return msg;
                });

        stringRedisTemplate.opsForValue()
                .set(orderKey, String.valueOf(order.getId()), USER_TTL_SECONDS, TimeUnit.SECONDS);
        return order.getId();
    }

    @Override
    public void markSuccess(Long itemId, Long userId, Long orderId) {
        try {
            stringRedisTemplate.opsForValue().set(RESULT_KEY + itemId + ":" + userId,
                    RESULT_SUCCESS + orderId, seckillProperties.getResultTtlMinutes(), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("写入秒杀成功结果失败, userId={}, itemId={}, orderId={}", userId, itemId, orderId, e);
        }
    }

    @Override
    public void markFailed(SeckillOrderMessage message, String reason) {
        if (message == null) {
            return;
        }
        Long itemId = message.getItemId();
        Long userId = message.getUserId();
        rollbackReservation(itemId, userId);
        try {
            stringRedisTemplate.opsForValue().set(RESULT_KEY + itemId + ":" + userId,
                    RESULT_FAIL + (reason == null ? "秒杀失败" : reason),
                    seckillProperties.getResultTtlMinutes(), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("写入秒杀失败结果失败, userId={}, itemId={}", userId, itemId, e);
        }
    }

    /**
     * 回补 Redis 库存、解除限购、清理排队/幂等标记
     */
    private void rollbackReservation(Long itemId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(STOCK_KEY + itemId);
            stringRedisTemplate.opsForSet().remove(USERS_KEY + itemId, String.valueOf(userId));
            stringRedisTemplate.delete(RESULT_KEY + itemId + ":" + userId);
            stringRedisTemplate.delete(ORDER_KEY + itemId + ":" + userId);
        } catch (Exception e) {
            log.error("回补秒杀库存失败, userId={}, itemId={}", userId, itemId, e);
        }
    }

    private SeckillProperties.Activity findActivity(Long itemId) {
        List<SeckillProperties.Activity> activities = seckillProperties.getActivities();
        if (activities == null) {
            return null;
        }
        return activities.stream()
                .filter(activity -> itemId.equals(activity.getItemId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 懒初始化库存：取「活动库存」与「DB 库存」较小值，避免活动库存超过真实库存
     */
    private int initStock(SeckillProperties.Activity activity, Integer dbStock) {
        int stock = activity.getStock() == null ? 0 : activity.getStock();
        if (dbStock != null) {
            stock = Math.min(stock, dbStock);
        }
        try {
            stringRedisTemplate.opsForValue()
                    .setIfAbsent(STOCK_KEY + activity.getItemId(), String.valueOf(stock));
        } catch (Exception e) {
            log.error("初始化秒杀库存失败, itemId={}", activity.getItemId(), e);
            throw new BizIllegalException("秒杀服务繁忙，请稍后重试！");
        }
        return stock;
    }

    private void ensureStockInitialized(SeckillProperties.Activity activity) {
        Boolean exists = stringRedisTemplate.hasKey(STOCK_KEY + activity.getItemId());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        initStockFromDb(activity);
    }

    private void initStockFromDb(SeckillProperties.Activity activity) {
        Integer dbStock = null;
        List<ItemDTO> items = itemClient.queryItemByIds(Collections.singletonList(activity.getItemId()));
        if (items != null && !items.isEmpty()) {
            dbStock = items.get(0).getStock();
        }
        initStock(activity, dbStock);
    }

    private int currentStock(Long itemId) {
        String value = stringRedisTemplate.opsForValue().get(STOCK_KEY + itemId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /**
     * 校验收货地址存在且属于当前用户，文案与普通下单保持一致
     */
    private void validateAddress(Long addressId) {
        AddressDTO address;
        try {
            address = userClient.queryAddressById(addressId);
        } catch (Throwable e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("收货地址不存在或不属于")) {
                throw new BadRequestException("收货地址不存在或不属于当前登录用户");
            }
            throw new BadRequestException("收货地址校验失败，请稍后重试");
        }
        if (address == null) {
            throw new BadRequestException("收货地址不存在");
        }
    }
}
