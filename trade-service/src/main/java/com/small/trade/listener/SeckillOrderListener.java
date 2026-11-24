package com.small.trade.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.small.trade.constants.MQConstants;
import com.small.trade.domain.dto.SeckillOrderMessage;
import com.small.trade.service.ISeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀异步下单消费者：Redis 预减成功的请求在 MQ 中排队落单。
 *
 * @author YL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderListener {

    private final ISeckillService seckillService;
    private final ObjectMapper objectMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.SECKILL_ORDER_QUEUE_NAME),
            exchange = @Exchange(name = MQConstants.SECKILL_EXCHANGE_NAME, type = ExchangeTypes.DIRECT),
            key = MQConstants.SECKILL_ORDER_KEY
    ))
    public void listenSeckillOrder(String payload) {
        SeckillOrderMessage message;
        try {
            message = objectMapper.readValue(payload, SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("秒杀消息解析失败, payload={}", payload, e);
            return;
        }
        try {
            Long orderId = seckillService.createSeckillOrder(message);
            seckillService.markSuccess(message.getItemId(), message.getUserId(), orderId);
            log.info("秒杀订单创建成功, userId={}, itemId={}, orderId={}",
                    message.getUserId(), message.getItemId(), orderId);
        } catch (Exception e) {
            log.error("秒杀异步下单失败, userId={}, itemId={}", message.getUserId(), message.getItemId(), e);
            String reason = e.getMessage() == null ? "秒杀下单失败，库存已释放" : e.getMessage();
            seckillService.markFailed(message, reason);
        }
    }
}
