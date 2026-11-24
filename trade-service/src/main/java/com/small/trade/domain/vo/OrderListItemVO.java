package com.small.trade.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(description = "订单列表项VO")
public class OrderListItemVO extends OrderVO {

    @ApiModelProperty("订单商品明细")
    private List<OrderDetailVO> details;
}
