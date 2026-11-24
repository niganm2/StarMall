package com.small.trade.controller;

import com.small.common.domain.PageDTO;
import com.small.trade.domain.dto.OrderFormDTO;
import com.small.trade.domain.vo.OrderListItemVO;
import com.small.trade.domain.vo.OrderVO;
import com.small.trade.service.IOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Api(tags = "订单管理接口")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final IOrderService orderService;

    @ApiOperation("根据id查询订单（只能查自己的订单）")
    @ApiImplicitParam(name = "id", value = "订单id", paramType = "path")
    @GetMapping("{id}")
    public OrderVO queryOrderById(@PathVariable("id") Long orderId) {
        return orderService.queryOrderById(orderId);
    }

    @ApiOperation("创建订单")
    @PostMapping
    public Long createOrder(@RequestBody OrderFormDTO orderFormDTO){
        return orderService.createOrder(orderFormDTO);
    }

    @ApiOperation("分页查询我的订单")
    @GetMapping
    public PageDTO<OrderListItemVO> queryMyOrders(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "5") Integer pageSize,
            @RequestParam(value = "status", required = false) Integer status) {
        return orderService.queryMyOrders(pageNo, pageSize, status);
    }

    @ApiOperation("查询我的订单数量")
    @ApiImplicitParam(name = "status", value = "订单状态，不传则统计全部")
    @GetMapping("/count")
    public Long queryMyOrderCount(@RequestParam(value = "status", required = false) Integer status) {
        return orderService.queryMyOrderCount(status);
    }

    @ApiOperation("取消订单（仅未付款订单可取消，取消后库存自动恢复）")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}/cancel")
    public void cancelOrder(@PathVariable("orderId") Long orderId) {
        orderService.cancelOrder(orderId);
    }

    @ApiOperation("模拟发货（仅已付款待发货订单）")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}/ship")
    public void shipOrder(@PathVariable("orderId") Long orderId) {
        orderService.shipOrder(orderId);
    }

    @ApiOperation("申请退款（已付款未收货的订单，余额原路退回并恢复库存）")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path"),
            @ApiImplicitParam(name = "reason", value = "退款原因", paramType = "query")
    })
    @PostMapping("/{orderId}/refund")
    public void refundOrder(@PathVariable("orderId") Long orderId,
                            @RequestParam(value = "reason", required = false) String reason) {
        orderService.refundOrder(orderId, reason);
    }

    @ApiOperation("删除订单（只能删自己的订单）")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @DeleteMapping("/{orderId}")
    public void deleteOrder(@PathVariable("orderId") Long orderId) {
        orderService.deleteOrder(orderId);
    }
}
