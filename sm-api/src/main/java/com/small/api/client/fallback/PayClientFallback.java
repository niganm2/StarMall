package com.small.api.client.fallback;

import com.small.api.client.PayClient;
import com.small.api.dto.PayOrderDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

@Slf4j
public class PayClientFallback implements FallbackFactory<PayClient> {
    @Override
    public PayClient create(Throwable cause) {
        return new PayClient() {
            @Override
            public PayOrderDTO queryPayOrderByBizOrderNo(Long id) {
                return null;
            }

            @Override
            public void refundPayOrder(Long bizOrderNo) {
                log.error("调用支付服务退款失败，bizOrderNo={}", bizOrderNo, cause);
                throw new RuntimeException("支付服务繁忙，退款失败，请稍后重试", cause);
            }
        };
    }
}