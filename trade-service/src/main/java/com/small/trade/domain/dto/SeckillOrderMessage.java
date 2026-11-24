package com.small.trade.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀异步下单消息体（JSON 传输）。
 *
 * @author YL
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {
    /**
     * 抢购用户 id
     */
    private Long userId;
    /**
     * 商品 id
     */
    private Long itemId;
    /**
     * 收货地址 id
     */
    private Long addressId;
    /**
     * 支付类型：默认 3-扣减余额
     */
    private Integer paymentType;
    /**
     * 秒杀成交价（分）
     */
    private Integer price;
}
