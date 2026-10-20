package com.small.api.dto;

import lombok.Data;

/**
 * 商品信息（复制自 sm-api，去除 javax 时代的 swagger 注解以适配 Boot 3）。
 */
@Data
public class ItemDTO {
    private Long id;
    private String name;
    private Integer price;
    private Integer stock;
    private String image;
    private String category;
    private String brand;
    private String spec;
    private Integer sold;
    private Integer commentCount;
    private Integer status;
}
