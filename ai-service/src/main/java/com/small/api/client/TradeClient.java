package com.small.api.client;

import com.small.api.dto.OrderFormDTO;
import com.small.api.dto.OrderVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 订单服务客户端。
 * 写操作（createOrder/cancelOrder/deleteOrder）显式携带 user-info：流式对话时工具在 Reactor 线程执行，
 * 无法从 RequestContextHolder 取身份。
 */
@FeignClient("trade-service")
public interface TradeClient {

    /**
     * 根据订单 id 查询订单详情。
     */
    @GetMapping("/orders/{orderId}")
    OrderVO queryOrderById(@PathVariable("orderId") Long orderId);

    /**
     * 创建订单（基于表单中的商品明细下单，返回订单 id）。
     */
    @PostMapping("/orders")
    Long createOrder(@RequestHeader("user-info") String userId, @RequestBody OrderFormDTO orderFormDTO);

    /**
     * 取消订单（仅未付款订单可取消，取消后库存自动恢复）。
     */
    @PutMapping("/orders/{orderId}/cancel")
    void cancelOrder(@RequestHeader("user-info") String userId, @PathVariable("orderId") Long orderId);

    /**
     * 删除订单（物理删除，连同订单明细和物流记录一起删除）。
     */
    @DeleteMapping("/orders/{orderId}")
    void deleteOrder(@RequestHeader("user-info") String userId, @PathVariable("orderId") Long orderId);
}
