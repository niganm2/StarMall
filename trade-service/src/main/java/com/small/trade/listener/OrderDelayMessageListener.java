package com.small.trade.listener;

import com.small.api.client.PayClient;
import com.small.api.dto.PayOrderDTO;
import com.small.trade.constants.MQConstants;
import com.small.trade.domain.po.Order;
import com.small.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {
    private final IOrderService orderService;
    private final PayClient payClient;
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.DELAY_ORDER_QUEUE_NAME),
            exchange = @Exchange(name = MQConstants.DELAY_EXCHANGE_NAME, type = ExchangeTypes.DIRECT),
            key = MQConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(Long orderId) {
        //1.查询订单
        Order order = orderService.getById(orderId);
        //2.判断订单是否支付成功
        if(order == null || !order.getStatus().equals(1)){
            //订单不存在或者已经支付成功
            return;
        }
        //3.未支付，查询订单支付流水状态
        PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(orderId);
        //4.判断是否支付
        if(payOrder != null && payOrder.getStatus().equals(3)){
            //4.1 已支付，修改订单状态为已支付
            orderService.markOrderPaySuccess(orderId);
        }else {
            //4.2 未支付，取消订单，恢复库存
            orderService.cancelOrder(orderId);
        }
    }
}
