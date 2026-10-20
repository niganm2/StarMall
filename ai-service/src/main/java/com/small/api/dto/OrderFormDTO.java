package com.small.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 创建订单表单（复制自 trade-service 的 OrderFormDTO）。
 */
@Data
public class OrderFormDTO {
    private Long addressId;
    private Integer paymentType;
    private List<OrderDetailDTO> details;
}
