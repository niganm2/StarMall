package com.small.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.small.common.domain.PageDTO;
import com.small.trade.domain.dto.OrderFormDTO;
import com.small.trade.domain.po.Order;
import com.small.trade.domain.vo.OrderListItemVO;
import com.small.trade.domain.vo.OrderVO;


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

    void shipOrder(Long orderId);

    void refundOrder(Long orderId, String reason);

    PageDTO<OrderListItemVO> queryMyOrders(Integer pageNo, Integer pageSize, Integer status);

    Long queryMyOrderCount(Integer status);

    OrderVO queryOrderById(Long orderId);

    void deleteOrder(Long orderId);
}
