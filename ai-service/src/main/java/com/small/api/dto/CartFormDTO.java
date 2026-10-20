package com.small.api.dto;

import lombok.Data;

/**
 * 加入购物车表单（复制自 cart-service 的 CartFormDTO）。
 */
@Data
public class CartFormDTO {
    private Long itemId;
    private String name;
    private String spec;
    private Integer price;
    private String image;
}
