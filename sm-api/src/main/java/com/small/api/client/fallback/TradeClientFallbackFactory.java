package com.small.api.client.fallback;

import com.small.api.client.TradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 订单服务降级：更新支付状态属于写操作，失败必须抛出业务异常，
 * 让支付流程明确回滚，不能假装成功。
 */
@Slf4j
public class TradeClientFallbackFactory implements FallbackFactory<TradeClient> {
    @Override
    public TradeClient create(Throwable cause) {
        return new TradeClient() {
            @Override
            public void markOrderPaySuccess(Long orderId) {
                log.error("更新订单支付状态失败，orderId={}", orderId, cause);
                throw new RuntimeException("订单服务繁忙，更新支付状态失败，请稍后重试", cause);
            }
        };
    }
}
