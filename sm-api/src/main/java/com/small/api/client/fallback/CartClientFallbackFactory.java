package com.small.api.client.fallback;

import com.small.api.client.CartClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collection;

/**
 * 购物车服务降级：删除购物车属于写操作，降级时必须抛出业务异常，
 * 不能静默返回成功，否则会出现"订单已创建但购物车未清理"的假成功。
 */
@Slf4j
public class CartClientFallbackFactory implements FallbackFactory<CartClient> {
    @Override
    public CartClient create(Throwable cause) {
        return new CartClient() {
            @Override
            public void deleteCartItemByIds(Collection<Long> ids) {
                log.error("删除购物车商品失败，ids={}", ids, cause);
                throw new RuntimeException("购物车服务繁忙，清理购物车失败，请稍后重试", cause);
            }
        };
    }
}
