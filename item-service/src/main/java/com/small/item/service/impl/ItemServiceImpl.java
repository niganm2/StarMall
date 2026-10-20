package com.small.item.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.OrderDetailDTO;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.item.cache.ItemCacheService;
import com.small.item.domain.po.Item;
import com.small.item.mapper.ItemMapper;
import com.small.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author YL
 */
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    private final ItemCacheService itemCacheService;

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
        }
        return result;
    }

    @Override
    public boolean updateById(Item entity) {
        boolean result = super.updateById(entity);
        if (result) {
            itemCacheService.evict(entity.getId());
        }
        return result;
    }

    @Override
    public boolean removeById(Serializable id) {
        boolean result = super.removeById(id);
        if (result) {
            itemCacheService.evict(Long.valueOf(String.valueOf(id)));
        }
        return result;
    }
}
