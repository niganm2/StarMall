package com.small.api.client.fallback;

import com.small.api.client.UserClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 用户服务降级：扣减余额属于写操作，失败必须抛出业务异常，
 * 避免"钱没扣但支付单已完成"的资损风险。
 */
@Slf4j
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {
    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public void deductMoney(String pw, Integer amount) {
                log.error("扣减用户余额失败，amount={}", amount, cause);
                throw new RuntimeException("用户服务繁忙，扣减余额失败，请稍后重试", cause);
            }
        };
    }
}
