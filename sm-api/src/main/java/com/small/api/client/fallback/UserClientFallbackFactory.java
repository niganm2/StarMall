package com.small.api.client.fallback;

import com.small.api.client.UserClient;
import com.small.api.dto.AddressDTO;
import feign.FeignException;
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
                String msg = "用户服务繁忙，扣减余额失败，请稍后重试";
                if (cause instanceof FeignException) {
                    String body = ((FeignException) cause).contentUTF8();
                    if (body != null && body.contains("密码")) {
                        msg = "支付密码错误，请重新输入";
                    } else if (body != null && body.contains("余额")) {
                        msg = "余额不足，请更换支付方式";
                    }
                }
                throw new RuntimeException(msg, cause);
            }

            @Override
            public void refundMoney(Integer amount) {
                log.error("退款到用户余额失败，amount={}", amount, cause);
                throw new RuntimeException("用户服务繁忙，退款失败，请稍后重试", cause);
            }

            @Override
            public AddressDTO queryAddressById(Long addressId) {
                log.error("查询收货地址失败，addressId={}", addressId, cause);
                String msg = "收货地址校验失败，请稍后重试";
                if (cause instanceof FeignException) {
                    String body = ((FeignException) cause).contentUTF8();
                    if (body != null && body.contains("地址")) {
                        msg = "收货地址不存在或不属于当前登录用户";
                    }
                }
                throw new RuntimeException(msg, cause);
            }
        };
    }
}
