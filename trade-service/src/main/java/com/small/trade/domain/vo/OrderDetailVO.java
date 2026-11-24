package com.small.trade.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel(description = "订单明细VO")
public class OrderDetailVO {
    @ApiModelProperty("订单详情id")
    private Long id;
    @ApiModelProperty("订单id")
    private Long orderId;
    @ApiModelProperty("商品id")
    private Long itemId;
    @ApiModelProperty("购买数量")
    private Integer num;
    @ApiModelProperty("商品标题")
    private String name;
    @ApiModelProperty("商品规格")
    private String spec;
    @ApiModelProperty("价格，单位：分")
    private Integer price;
    @ApiModelProperty("商品图片")
    private String image;
}
