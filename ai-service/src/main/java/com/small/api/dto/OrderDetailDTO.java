package com.small.api.dto;

import lombok.Data;

/**
 * 订单明细（复制自 sm-api 的 OrderDetailDTO，仅保留下单必需字段）。
 */
@Data
public class OrderDetailDTO {
    private Long itemId;
    private Integer num;
}
