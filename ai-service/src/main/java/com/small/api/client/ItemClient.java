package com.small.api.client;

import com.small.api.dto.ItemDTO;
import com.small.api.dto.PageResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * 商品服务客户端。
 */
@FeignClient("item-service")
public interface ItemClient {

    /**
     * 根据 id 批量查询商品。
     */
    @GetMapping("/items")
    List<ItemDTO> queryItemByIds(@RequestParam("ids") Collection<Long> ids);

    /**
     * 根据 id 查询单个商品。
     */
    @GetMapping("/items/{id}")
    ItemDTO queryItemById(@PathVariable("id") Long id);

    /**
     * 搜索商品（关键字/品牌/分类/价格区间）。
     */
    @GetMapping("/search/list")
    PageResult<ItemDTO> searchItems(@RequestParam(value = "key", required = false) String key,
                                    @RequestParam(value = "brand", required = false) String brand,
                                    @RequestParam(value = "category", required = false) String category,
                                    @RequestParam(value = "minPrice", required = false) Integer minPrice,
                                    @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
                                    @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                    @RequestParam(value = "pageSize", required = false) Integer pageSize);

    /**
     * 查询在售商品的分类列表。
     */
    @GetMapping("/search/categories")
    List<String> queryCategories();
}
