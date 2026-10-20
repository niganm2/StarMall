package com.small.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.trade.mapper.OrderDetailMapper;
import com.small.trade.service.IOrderDetailService;
import org.springframework.stereotype.Service;
import com.small.trade.domain.po.OrderDetail;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
@Service
public class OrderDetailServiceImpl extends ServiceImpl<OrderDetailMapper, OrderDetail> implements IOrderDetailService {

}
