package com.small.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.small.api.dto.OrderDetailDTO;
import com.small.item.domain.po.Item;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 扣减库存：加上 stock >= num 守卫，避免并发下超卖（影响行数为 0 表示库存不足）
     */
    @Update("UPDATE item SET stock = stock - #{num} WHERE id = #{itemId} AND stock >= #{num}")
    int updateStock(OrderDetailDTO orderDetail);

    @Update("UPDATE item SET stock = stock + #{num} WHERE id = #{itemId}")
    void restoreStock(OrderDetailDTO orderDetail);
}
