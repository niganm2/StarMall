package com.small.trade.domain.vo;

import lombok.Data;

/**
 * 秒杀结果：NONE-未参与，QUEUING-排队中，SUCCESS-下单成功，FAIL-下单失败。
 *
 * @author YL
 */
@Data
public class SeckillResultVO {
    private String status;
    private Long orderId;
    private String msg;
}
