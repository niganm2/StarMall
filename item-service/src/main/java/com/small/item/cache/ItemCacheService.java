package com.small.item.cache;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.small.api.dto.ItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;

/**
 * 商品查询缓存：一级 Caffeine 本地缓存 + 二级 Redis 缓存。
 * <p>
 * 读请求先查本地缓存（微秒级），未命中再查 Redis，都未命中才回源数据库，
 * 从而把绝大部分商品查询挡在数据库之外；写请求（商品变更、库存扣减）主动清除缓存，保证一致性。
 *
 * @author YL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheService {

    /**
     * Redis 缓存 key 前缀
     */
    private static final String KEY_PREFIX = "item:";

    /**
     * Redis 缓存过期时间
     */
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);

    /**
     * 本地缓存过期时间
     */
    private static final Duration LOCAL_TTL = Duration.ofMinutes(5);

    /**
     * 一级缓存：最多缓存 1 万个商品，写入 5 分钟后过期
     */
    private final Cache<Long, ItemDTO> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 查询商品缓存：本地缓存 -> Redis -> null（未命中）
     */
    public ItemDTO get(Long id) {
        if (id == null) {
            return null;
        }
        // 1.先查一级缓存（Caffeine）
        ItemDTO item = localCache.getIfPresent(id);
        if (item != null) {
            return item;
        }
        // 2.再查二级缓存（Redis），命中后回填一级缓存
        String json = readFromRedis(id);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        item = toItemDTO(json);
        if (item != null) {
            localCache.put(id, item);
        }
        return item;
    }

    /**
     * 写入商品缓存：同时写入一级缓存与二级缓存
     */
    public void put(ItemDTO item) {
        if (item == null || item.getId() == null) {
            return;
        }
        localCache.put(item.getId(), item);
        try {
            stringRedisTemplate.opsForValue()
                    .set(KEY_PREFIX + item.getId(), objectMapper.writeValueAsString(item), REDIS_TTL);
        } catch (Exception e) {
            log.error("商品缓存写入 Redis 失败, itemId={}", item.getId(), e);
        }
    }

    /**
     * 清除商品缓存：商品信息或库存发生变更后调用，避免缓存与数据库不一致
     */
    public void evict(Long id) {
        if (id == null) {
            return;
        }
        localCache.invalidate(id);
        try {
            stringRedisTemplate.delete(KEY_PREFIX + id);
        } catch (Exception e) {
            log.error("商品缓存清除 Redis 失败, itemId={}", id, e);
        }
    }

    /**
     * 批量清除商品缓存
     */
    public void evictAll(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ids.forEach(this::evict);
    }

    /**
     * 读取 Redis 缓存，Redis 不可用时降级为未命中，不影响正常业务
     */
    private String readFromRedis(Long id) {
        try {
            return stringRedisTemplate.opsForValue().get(KEY_PREFIX + id);
        } catch (Exception e) {
            log.error("商品缓存读取 Redis 失败, itemId={}", id, e);
            return null;
        }
    }

    private ItemDTO toItemDTO(String json) {
        try {
            return objectMapper.readValue(json, ItemDTO.class);
        } catch (Exception e) {
            log.error("商品缓存反序列化失败", e);
            return null;
        }
    }
}
