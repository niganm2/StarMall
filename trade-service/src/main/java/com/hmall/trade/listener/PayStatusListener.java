package com.hmall.trade.listener;

import com.hmall.trade.domain.po.Order;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PayStatusListener {

    private final IOrderService orderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "trade.pay.success.queue", durable = "true"),
            exchange = @Exchange(name = "pay,direct"),
            key = "pay.success"
    ))
    public void listenPsySuccess(Long orderId){
        //1.查询订单
        Order order = orderService.getById(orderId);
        //2.判断订单状态是不是未支付
        if(order == null || !order.getStatus().equals(1)){
            //不做处理
            return;
        }
        //3.标记订单状态为已支付
        System.out.println("支付成功，开始处理订单,orderId为" + orderId);
        orderService.markOrderPaySuccess(orderId);
    }

}
