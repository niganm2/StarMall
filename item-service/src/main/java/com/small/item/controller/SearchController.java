package com.small.item.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.small.api.dto.ItemDTO;
import com.small.common.domain.PageDTO;
import com.small.item.domain.po.Item;
import com.small.item.domain.query.ItemPageQuery;
import com.small.item.service.IItemService;
import com.small.item.service.ItemSearchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final IItemService itemService;
    private final ItemSearchService itemSearchService;

    @ApiOperation("搜索商品：ES 检索，异常时回退 MySQL")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        try {
            return itemSearchService.search(query);
        } catch (Exception e) {
            log.warn("ES 搜索失败，回退 MySQL：{}", e.getMessage());
            return searchByMysql(query);
        }
    }

    @ApiOperation("搜索过滤项：按当前关键字返回可选品牌与分类")
    @PostMapping("/filters")
    public Map<String, List<String>> filters(@RequestBody(required = false) ItemPageQuery query) {
        ItemPageQuery q = query == null ? new ItemPageQuery() : query;
        try {
            return itemSearchService.filters(q);
        } catch (Exception e) {
            log.warn("ES 聚合失败，回退 MySQL：{}", e.getMessage());
            return filtersByMysql(q);
        }
    }

    @ApiOperation("搜索联想：根据关键字返回最多10个商品名称")
    @GetMapping("/suggestion")
    public List<String> suggestion(@RequestParam(value = "key", required = false) String key) {
        if (StrUtil.isBlank(key)) {
            return Collections.emptyList();
        }
        try {
            return itemSearchService.suggestion(key);
        } catch (Exception e) {
            log.warn("ES 联想失败，回退 MySQL：{}", e.getMessage());
            return suggestionByMysql(key);
        }
    }

    @ApiOperation("查询在售商品的分类列表")
    @GetMapping("/categories")
    public List<String> categories() {
        try {
            return itemSearchService.categories();
        } catch (Exception e) {
            log.warn("ES 分类查询失败，回退 MySQL：{}", e.getMessage());
            return itemService.list(new QueryWrapper<Item>()
                            .select("DISTINCT category")
                            .eq("status", 1))
                    .stream()
                    .map(Item::getCategory)
                    .filter(StrUtil::isNotBlank)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private PageDTO<ItemDTO> searchByMysql(ItemPageQuery query) {
        Page<Item> result = itemService.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .page(query.toMpPage("update_time", false));
        return PageDTO.of(result, ItemDTO.class);
    }

    private Map<String, List<String>> filtersByMysql(ItemPageQuery query) {
        List<Item> items = itemService.lambdaQuery()
                .select(Item::getBrand, Item::getCategory)
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .last("limit 2000")
                .list();
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("brand", items.stream().map(Item::getBrand)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(Collectors.toList()));
        result.put("category", items.stream().map(Item::getCategory)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(Collectors.toList()));
        return result;
    }

    private List<String> suggestionByMysql(String key) {
        return itemService.lambdaQuery()
                .select(Item::getName)
                .like(Item::getName, key)
                .eq(Item::getStatus, 1)
                .last("limit 10")
                .list()
                .stream()
                .map(Item::getName)
                .distinct()
                .collect(Collectors.toList());
    }
}
