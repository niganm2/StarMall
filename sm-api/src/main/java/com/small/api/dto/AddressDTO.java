package com.small.api.dto;

import lombok.Data;

/**
 * 收货地址DTO（微服务间调用用）
 */
@Data
public class AddressDTO {
    private Long id;
    private Long userId;
}
