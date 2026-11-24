package com.small.trade.domain.vo;

import lombok.Data;

/**
 * 秒杀活动商品展示信息。
 *
 * @author YL
 */
@Data
public class SeckillItemVO {
    private Long itemId;
    private String name;
    private String image;
    /**
     * 原价（分）
     */
    private Integer originalPrice;
    /**
     * 秒杀价（分）
     */
    private Integer seckillPrice;
    /**
     * 剩余秒杀库存
     */
    private Integer stock;
    /**
     * 活动总库存
     */
    private Integer totalStock;
    /**
     * 是否已售罄
     */
    private Boolean soldOut;
}
