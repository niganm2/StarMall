package com.small.trade.service;

import com.small.trade.domain.dto.SeckillOrderMessage;
import com.small.trade.domain.vo.SeckillItemVO;
import com.small.trade.domain.vo.SeckillResultVO;

import java.util.List;

/**
 * 秒杀服务：Redis 预减库存 + MQ 异步下单。
 *
 * @author YL
 */
public interface ISeckillService {

    /**
     * 查询秒杀活动商品列表（含 Redis 剩余库存）
     */
    List<SeckillItemVO> queryActivities();

    /**
     * 秒杀抢购：Redis Lua 原子预减库存 + 一人一单，成功后发送 MQ 异步下单
     *
     * @return 排队状态 QUEUING
     */
    String seckill(Long itemId, Long addressId);

    /**
     * 查询当前用户的秒杀结果
     */
    SeckillResultVO queryResult(Long itemId);

    /**
     * MQ 消费：创建秒杀订单（本地事务）
     */
    Long createSeckillOrder(SeckillOrderMessage message);

    /**
     * 标记异步下单成功
     */
    void markSuccess(Long itemId, Long userId, Long orderId);

    /**
     * 标记异步下单失败：回补 Redis 库存、移除限购记录、写入失败原因
     */
    void markFailed(SeckillOrderMessage message, String reason);
}
