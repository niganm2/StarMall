package com.small.pay.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.api.client.TradeClient;
import com.small.api.client.UserClient;
import com.small.common.exception.BadRequestException;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.common.utils.UserContext;
import com.small.pay.domain.dto.PayApplyDTO;
import com.small.pay.domain.dto.PayOrderFormDTO;
import com.small.pay.domain.po.PayOrder;
import com.small.pay.enums.PayStatus;
import com.small.pay.mapper.PayOrderMapper;
import com.small.pay.service.IPayOrderService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 支付订单 服务实现类
 * </p>
 *
 * @author YL
 * @since 2026-05-16
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    private final UserClient userClient;

//    private final TradeClient tradeClient;

    private final RabbitTemplate rabbitTemplate;

    @Override
    public String applyPayOrder(PayApplyDTO applyDTO) {
        // 1.幂等性校验
        PayOrder payOrder = checkIdempotent(applyDTO);
        // 2.返回结果
        return payOrder.getId().toString();
    }

    @Override
    @Transactional
    public void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO) {
        // 1.查询支付单
        PayOrder po = getById(payOrderFormDTO.getId());
        // 2.判断状态
        if (!PayStatus.WAIT_BUYER_PAY.equalsValue(po.getStatus())) {
            // 订单不是未支付，状态异常：属于客户端业务错误，返回 400 而非 500
            throw new BadRequestException("交易已支付或关闭！");
        }
        // 3.尝试扣减余额（密码错误、余额不足要给出明确提示）
        try {
            userClient.deductMoney(payOrderFormDTO.getPw(), po.getAmount());
        } catch (Throwable e) {
            // Sentinel 会把 Feign 降级抛出的异常包装成 AssertionError，这里按消息内容还原业务提示
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("支付密码错误")) {
                throw new BadRequestException("支付密码错误，请重新输入");
            }
            if (msg.contains("余额不足")) {
                throw new BadRequestException("余额不足，请更换支付方式");
            }
            throw new BadRequestException("暂时无法完成支付，请稍后重试");
        }
        // 4.修改支付单状态
        boolean success = markPayOrderSuccess(payOrderFormDTO.getId(), LocalDateTime.now());
        if (!success) {
            throw new BadRequestException("交易已支付或关闭！");
        }
        //5.修改订单状态
//        tradeClient.markOrderPaySuccess(po.getBizOrderNo());
        try {
            rabbitTemplate.convertAndSend("pay.direct", "pay.success", po.getBizOrderNo());
        } catch (AmqpException e) {
            log.error("发送支付成功消息失败,订单id：{}", po.getBizOrderNo(), e);
        }
    }

    @Override
    @Transactional
    public void refundPayOrder(Long bizOrderNo) {
        // 1.查询支付单
        PayOrder po = queryByBizOrderNo(bizOrderNo);
        if (po == null) {
            throw new BizIllegalException("支付单不存在，无法退款");
        }
        // 2.只有支付成功的支付单才能退款
        if (!PayStatus.TRADE_SUCCESS.equalsValue(po.getStatus())) {
            throw new BizIllegalException("当前支付单状态不支持退款");
        }
        // 3.原路退回：余额支付退回用户余额
        userClient.refundMoney(po.getAmount());
        // 4.更新支付单状态为已退款（带状态乐观锁）
        boolean success = lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.REFUNDED.getValue())
                .set(PayOrder::getUpdateTime, LocalDateTime.now())
                .eq(PayOrder::getId, po.getId())
                .eq(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .update();
        if (!success) {
            throw new BizIllegalException("退款失败，支付单状态已变更");
        }
    }

    public boolean markPayOrderSuccess(Long id, LocalDateTime successTime) {
        return lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, id)
                // 支付状态的乐观锁判断
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
    }


    private PayOrder checkIdempotent(PayApplyDTO applyDTO) {
        // 1.首先查询支付单
        PayOrder oldOrder = queryByBizOrderNo(applyDTO.getBizOrderNo());
        // 2.判断是否存在
        if (oldOrder == null) {
            // 不存在支付单，说明是第一次，写入新的支付单并返回
            PayOrder payOrder = buildPayOrder(applyDTO);
            payOrder.setPayOrderNo(IdWorker.getId());
            save(payOrder);
            return payOrder;
        }
        // 3.旧单已经存在，判断是否支付成功
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            // 已经支付成功，抛出异常
            throw new BizIllegalException("订单已经支付！");
        }
        // 4.旧单已经存在，判断是否已经关闭
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            // 已经关闭，抛出异常
            throw new BizIllegalException("订单已关闭");
        }
        // 5.旧单已经存在，判断支付渠道是否一致
        if (!StringUtils.equals(oldOrder.getPayChannelCode(), applyDTO.getPayChannelCode())) {
            // 支付渠道不一致，需要重置数据，然后重新申请支付单
            PayOrder payOrder = buildPayOrder(applyDTO);
            payOrder.setId(oldOrder.getId());
            payOrder.setQrCodeUrl("");
            updateById(payOrder);
            payOrder.setPayOrderNo(oldOrder.getPayOrderNo());
            return payOrder;
        }
        // 6.旧单已经存在，且可能是未支付或未提交，且支付渠道一致，直接返回旧数据
        return oldOrder;
    }

    private PayOrder buildPayOrder(PayApplyDTO payApplyDTO) {
        // 1.数据转换
        PayOrder payOrder = BeanUtils.toBean(payApplyDTO, PayOrder.class);
        // 2.初始化数据
        payOrder.setPayOverTime(LocalDateTime.now().plusMinutes(120L));
        payOrder.setStatus(PayStatus.WAIT_BUYER_PAY.getValue());
        payOrder.setBizUserId(UserContext.getUser());
        return payOrder;
    }

    public PayOrder queryByBizOrderNo(Long bizOrderNo) {
        return lambdaQuery()
                .eq(PayOrder::getBizOrderNo, bizOrderNo)
                .one();
    }
}
