package com.small.trade.controller;

import com.small.trade.domain.vo.SeckillItemVO;
import com.small.trade.domain.vo.SeckillResultVO;
import com.small.trade.service.ISeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 秒杀接口。路径放在 /orders/seckill 下，复用网关已有的 /orders/** 路由与登录校验。
 *
 * @author YL
 */
@Api(tags = "秒杀接口")
@RestController
@RequestMapping("/orders/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final ISeckillService seckillService;

    @ApiOperation("秒杀活动列表（含剩余库存）")
    @GetMapping("/items")
    public List<SeckillItemVO> queryActivities() {
        return seckillService.queryActivities();
    }

    @ApiOperation("立即秒杀：Redis 预减成功后进入 MQ 排队")
    @PostMapping("/{itemId}")
    public String seckill(@PathVariable("itemId") Long itemId,
                          @RequestBody(required = false) Map<String, Long> body) {
        Long addressId = body == null ? null : body.get("addressId");
        return seckillService.seckill(itemId, addressId);
    }

    @ApiOperation("查询秒杀结果")
    @GetMapping("/result/{itemId}")
    public SeckillResultVO queryResult(@PathVariable("itemId") Long itemId) {
        return seckillService.queryResult(itemId);
    }
}
