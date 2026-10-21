package com.small.trade.limit;

import com.small.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 按用户维度的下单频控：基于 Redis ZSET 的滑动窗口。
 * <p>
 * Sentinel 的 FlowRule 只能限制单个接口的整体 QPS，管不住"同一用户短时间狂点下单"，
 * 因此用 ZSET 记录每次下单的时间戳，窗口外的记录先清理，再判断窗口内请求数是否超限。
 * 整个"清理 + 计数 + 写入"过程由 Lua 脚本原子执行，避免并发下的判断-写入竞态。
 *
 * @author YL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRateLimiter {

    /**
     * 频控 key 前缀，后接用户 id
     */
    private static final String KEY_PREFIX = "order:limit:";

    /**
     * 滑动窗口脚本：ARGV = 当前时间戳(ms)、窗口大小(ms)、窗口内最大次数、本次请求标识
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1]\n" +
            "local now = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local limit = tonumber(ARGV[3])\n" +
            "redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)\n" +
            "local count = redis.call('ZCARD', key)\n" +
            "if count >= limit then\n" +
            "    return 0\n" +
            "end\n" +
            "redis.call('ZADD', key, now, ARGV[4])\n" +
            "redis.call('PEXPIRE', key, window)\n" +
            "return 1\n",
            Long.class);

    /**
     * 窗口大小，默认 1 分钟
     */
    @Value("${sm.trade.order-limit.window-ms:60000}")
    private long windowMs = 60000;

    /**
     * 窗口内允许的最大下单次数，默认 10 次
     */
    @Value("${sm.trade.order-limit.max-count:10}")
    private int maxCount = 10;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 校验当前用户在下单窗口内的请求次数，超限抛出业务异常
     *
     * @param userId 当前登录用户 id
     */
    public void check(Long userId) {
        String key = KEY_PREFIX + userId;
        long now = System.currentTimeMillis();
        // 同一毫秒内的多次请求需要不同的 member，避免 ZADD 覆盖导致计数偏小
        String member = now + "-" + System.nanoTime();
        try {
            Long allowed = stringRedisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(maxCount),
                    member);
            if (allowed != null && allowed == 0L) {
                log.warn("用户下单触发滑动窗口频控, userId={}, 窗口={}ms, 上限={}次", userId, windowMs, maxCount);
                throw new BadRequestException("下单过于频繁，每 " + windowMs / 1000
                        + " 秒最多 " + maxCount + " 次，请稍后再试");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            // 频控依赖不可用时放行，避免非核心组件故障阻断主交易链路
            log.error("下单频控校验失败，已放行本次请求, userId={}", userId, e);
        }
    }
}
