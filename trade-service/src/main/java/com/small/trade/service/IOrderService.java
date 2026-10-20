package com.small.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.small.trade.domain.dto.OrderFormDTO;
import com.small.trade.domain.po.Order;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
public interface IOrderService extends IService<Order> {

    Long createOrder(OrderFormDTO orderFormDTO);

    void markOrderPaySuccess(Long orderId);

    void cancelOrder(Long orderId);
}
