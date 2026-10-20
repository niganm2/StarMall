package com.small.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.domain.dto.ItemDTO;
import com.small.domain.dto.OrderDetailDTO;
import com.small.domain.po.Item;
import com.small.mapper.ItemMapper;
import com.small.service.IItemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author YL
 */
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

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
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        return BeanUtils.copyList(listByIds(ids), ItemDTO.class);
    }
}
