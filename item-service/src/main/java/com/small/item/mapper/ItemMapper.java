package com.small.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.small.api.dto.OrderDetailDTO;
import com.small.item.domain.po.Item;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 延迟关联分页：先在二级索引上取出目标页的主键，再回表取整行，
     * 避免大 offset 时全字段扫描并丢弃大量行；orderBy/order 由调用方白名单校验后传入
     */
    @Select("SELECT i.* FROM item i INNER JOIN (" +
            "SELECT id FROM item ORDER BY ${orderBy} ${order} LIMIT #{offset}, #{size}) t " +
            "ON i.id = t.id ORDER BY i.${orderBy} ${order}")
    List<Item> pageDeep(@Param("offset") long offset,
                        @Param("size") int size,
                        @Param("orderBy") String orderBy,
                        @Param("order") String order);
}
