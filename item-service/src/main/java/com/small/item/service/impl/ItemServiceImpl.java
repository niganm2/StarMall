package com.small.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.OrderDetailDTO;
import com.small.common.domain.PageDTO;
import com.small.common.domain.PageQuery;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.item.cache.ItemCacheService;
import com.small.item.domain.po.Item;
import com.small.item.mapper.ItemMapper;
import com.small.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author YL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    private final ItemCacheService itemCacheService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 商品总数缓存 key 与过期时间 */
    private static final String TOTAL_CACHE_KEY = "item:page:total";
    private static final Duration TOTAL_CACHE_TTL = Duration.ofSeconds(60);
    /** 热页结果缓存：key 前缀、版本号（写操作自增使旧缓存失效）、过期时间、热页上限 */
    private static final String PAGE_CACHE_PREFIX = "item:page:data:";
    private static final String PAGE_VERSION_KEY = "item:page:ver";
    private static final Duration PAGE_CACHE_TTL = Duration.ofSeconds(60);
    private static final int HOT_PAGE_MAX = 5;

    /** 最大页码：超过后直接返回空列表，避免超大 offset 的深分页慢查询 */
    private static final int MAX_PAGE_NO = 100;
    /** 单页最大条数 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 允许排序的字段白名单，防止 SQL 注入 */
    private static final Set<String> SORTABLE_COLUMNS =
            new HashSet<>(Arrays.asList("update_time", "price", "sold"));

    @Override
    public PageDTO<ItemDTO> queryItemPage(PageQuery query) {
        int pageNo = Math.max(1, query.getPageNo() == null ? 1 : query.getPageNo());
        int pageSize = Math.min(Math.max(1, query.getPageSize() == null ? 20 : query.getPageSize()),
                MAX_PAGE_SIZE);
        // 默认按更新时间倒序，与原有行为保持一致
        String column = "update_time";
        boolean asc = false;
        if (StrUtil.isNotEmpty(query.getSortBy()) && SORTABLE_COLUMNS.contains(query.getSortBy())) {
            column = query.getSortBy();
            asc = Boolean.TRUE.equals(query.getIsAsc());
        }
        // 热门页（前 5 页）优先读缓存，命中则完全不访问数据库
        String cacheKey = null;
        if (pageNo <= HOT_PAGE_MAX) {
            cacheKey = pageCacheKey(pageNo, pageSize, column, asc);
            PageDTO<ItemDTO> cached = readPageCache(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        long total = countTotal();
        long pages = (total + pageSize - 1) / pageSize;
        // 超出最大页码直接返回空页，不再打数据库
        if (pageNo > MAX_PAGE_NO) {
            return new PageDTO<>(total, pages, Collections.emptyList());
        }
        long offset = (long) (pageNo - 1) * pageSize;
        List<Item> items = baseMapper.pageDeep(offset, pageSize, column, asc ? "ASC" : "DESC");
        PageDTO<ItemDTO> result = new PageDTO<>(total, pages, BeanUtils.copyList(items, ItemDTO.class));
        if (cacheKey != null) {
            writePageCache(cacheKey, result);
        }
        return result;
    }

    /**
     * 统计商品总数：Redis 缓存 60 秒，避免高并发下每个请求都执行 count(*)
     */
    private long countTotal() {
        try {
            String cached = stringRedisTemplate.opsForValue().get(TOTAL_CACHE_KEY);
            if (StrUtil.isNotBlank(cached)) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取商品总数缓存失败，改为直接统计：{}", e.getMessage());
        }
        long total = baseMapper.selectCount(null);
        try {
            stringRedisTemplate.opsForValue()
                    .set(TOTAL_CACHE_KEY, String.valueOf(total), TOTAL_CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入商品总数缓存失败：{}", e.getMessage());
        }
        return total;
    }

    /**
     * 商品增删改后清除总数缓存，并递增分页缓存版本号，使旧的热页缓存整体失效
     */
    private void clearTotalCache() {
        try {
            stringRedisTemplate.delete(TOTAL_CACHE_KEY);
            stringRedisTemplate.opsForValue().increment(PAGE_VERSION_KEY);
        } catch (Exception e) {
            log.warn("清除商品总数缓存失败：{}", e.getMessage());
        }
    }

    private String pageCacheKey(int pageNo, int pageSize, String column, boolean asc) {
        String version = "0";
        try {
            String cached = stringRedisTemplate.opsForValue().get(PAGE_VERSION_KEY);
            if (StrUtil.isNotBlank(cached)) {
                version = cached;
            }
        } catch (Exception e) {
            log.warn("读取分页缓存版本失败：{}", e.getMessage());
        }
        return PAGE_CACHE_PREFIX + version + ":" + pageNo + ":" + pageSize + ":" + column + ":" + asc;
    }

    private PageDTO<ItemDTO> readPageCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<PageDTO<ItemDTO>>() {
            });
        } catch (Exception e) {
            log.warn("读取分页缓存失败：{}", e.getMessage());
            return null;
        }
    }

    private void writePageCache(String key, PageDTO<ItemDTO> page) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(page), PAGE_CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入分页缓存失败：{}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deductStock(List<OrderDetailDTO> items) {
        try {
            for (OrderDetailDTO item : items) {
                // 逐条条件更新：影响行数为 0 说明库存不足，抛异常触发整体回滚，避免部分扣减
                if (baseMapper.updateStock(item) == 0) {
                    throw new BizIllegalException("库存不足！");
                }
            }
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        // 库存已变更，清除对应商品缓存，避免缓存中的库存与数据库不一致
        items.forEach(item -> itemCacheService.evict(item.getItemId()));
    }

    @Override
    @Transactional
    public void restoreStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.small.item.mapper.ItemMapper.restoreStock";
        try {
            executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("恢复库存异常!", e);
        }
        // 库存已变更，清除对应商品缓存
        items.forEach(item -> itemCacheService.evict(item.getItemId()));
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ItemDTO> result = new ArrayList<>(ids.size());
        List<Long> missIds = new ArrayList<>();
        // 1.优先读取缓存，命中直接使用，未命中的记录后回源
        for (Long id : ids) {
            ItemDTO cached = itemCacheService.get(id);
            if (cached != null) {
                result.add(cached);
            } else {
                missIds.add(id);
            }
        }
        // 2.缓存未命中的部分查数据库，并写回缓存
        if (!missIds.isEmpty()) {
            List<ItemDTO> dbItems = BeanUtils.copyList(listByIds(missIds), ItemDTO.class);
            dbItems.forEach(itemCacheService::put);
            result.addAll(dbItems);
        }
        return result;
    }

    @Override
    public ItemDTO queryItemById(Long id) {
        if (id == null) {
            return null;
        }
        // 1.先查缓存
        ItemDTO item = itemCacheService.get(id);
        if (item != null) {
            return item;
        }
        // 2.缓存未命中再查数据库，并写回缓存
        Item dbItem = getById(id);
        if (dbItem == null) {
            return null;
        }
        item = BeanUtils.copyBean(dbItem, ItemDTO.class);
        itemCacheService.put(item);
        return item;
    }

    @Override
    public boolean save(Item entity) {
        boolean result = super.save(entity);
        if (result) {
            itemCacheService.evict(entity.getId());
            clearTotalCache();
        }
        return result;
    }

    @Override
    public boolean updateById(Item entity) {
        boolean result = super.updateById(entity);
        if (result) {
            itemCacheService.evict(entity.getId());
            clearTotalCache();
        }
        return result;
    }

    @Override
    public boolean removeById(Serializable id) {
        boolean result = super.removeById(id);
        if (result) {
            itemCacheService.evict(Long.valueOf(String.valueOf(id)));
            clearTotalCache();
        }
        return result;
    }
}
