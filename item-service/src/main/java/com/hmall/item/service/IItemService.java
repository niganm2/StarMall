package com.hmall.item.service;



import com.baomidou.mybatisplus.extension.service.IService;

import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
public interface IItemService extends IService<Item> {

    void deductStock(List<OrderDetailDTO> items);

    void restoreStock(List<OrderDetailDTO> items);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);
}
