package com.small.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单信息（复制自 trade-service 的 OrderVO）。
 */
@Data
public class OrderVO {
    private Long id;
    private Integer totalFee;
    private Integer paymentType;
    private Long userId;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime consignTime;
    private LocalDateTime endTime;
    private LocalDateTime closeTime;
    private LocalDateTime commentTime;
}
