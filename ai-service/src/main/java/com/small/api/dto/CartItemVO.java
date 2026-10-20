package com.small.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车条目（复制自 cart-service 的 CartVO）。
 */
@Data
public class CartItemVO {
    private Long id;
    private Long itemId;
    private Integer num;
    private String name;
    private String spec;
    private Integer price;
    private Integer newPrice;
    private Integer status;
    private Integer stock;
    private String image;
    private LocalDateTime createTime;
}
