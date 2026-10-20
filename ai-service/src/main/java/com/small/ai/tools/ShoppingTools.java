package com.small.ai.tools;

import com.small.api.client.ItemClient;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 导购场景工具：全部通过 OpenFeign 调用商品服务，不直连业务库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShoppingTools {

    private final ItemClient itemClient;

    @Tool(description = "按关键字搜索商品，可按品牌、分类、价格区间过滤，返回商品摘要列表（仅含 id、名称、价格）。价格参数单位为分。")
    public List<String> searchItems(
            @ToolParam(description = "搜索关键字，例如：手机、耳机、面霜；没有可传空", required = false) String key,
            @ToolParam(description = "品牌名称，例如：小米、华为；没有可传空", required = false) String brand,
            @ToolParam(description = "商品分类，必须使用 listCategories 返回的分类名（如：手机、曲面电视、硬盘、休闲鞋），没有可传空", required = false) String category,
            @ToolParam(description = "最低价，单位：分；没有可传空", required = false) Integer minPrice,
            @ToolParam(description = "最高价，单位：分；没有可传空", required = false) Integer maxPrice) {
        try {
            PageResult<ItemDTO> page = itemClient.searchItems(
                    StringUtils.hasText(key) ? key.trim() : null,
                    StringUtils.hasText(brand) ? brand.trim() : null,
                    StringUtils.hasText(category) ? category.trim() : null,
                    minPrice, maxPrice, 1, 5);
            if (page == null || page.getList() == null) {
                return Collections.emptyList();
            }
            // 只保留 id、名称、价格：减少上下文占用、加快回复，也避免模型照抄图片链接等冗余字段
            return page.getList().stream()
                    .map(item -> String.format("id：%d，名称：%s，价格：%s元",
                            item.getId(), item.getName(), toYuan(item.getPrice())))
                    .toList();
        } catch (Exception e) {
            log.error("搜索商品失败，key={}, brand={}, category={}", key, brand, category, e);
            return Collections.emptyList();
        }
    }

    @Tool(description = "查询商城在售商品的全部分类列表（如：手机、曲面电视、硬盘、休闲鞋、拉杆箱、牛奶等）。当用户提到笼统的品类词（例如：电子产品、数码、家电、日用品）或问『有哪些/有什么商品』时，必须先调用本工具获取真实分类，再按分类调用 searchItems，禁止把笼统词当作关键字或分类直接搜索")
    public List<String> listCategories() {
        try {
            List<String> categories = itemClient.queryCategories();
            return categories == null ? Collections.emptyList() : categories;
        } catch (Exception e) {
            log.error("查询商品分类失败", e);
            return Collections.emptyList();
        }
    }

    @Tool(description = "根据商品 id 查询单个商品的详细信息（名称、价格、库存、品牌、分类、规格）")
    public String getItemById(@ToolParam(description = "商品 id") Long itemId) {
        if (itemId == null) {
            return "未提供商品 id，无法查询。";
        }
        try {
            ItemDTO item = itemClient.queryItemById(itemId);
            if (item == null || item.getId() == null) {
                return "未找到 id 为 " + itemId + " 的商品。";
            }
            return String.format("商品id：%d，名称：%s，价格：%s元，库存：%s，品牌：%s，分类：%s，规格：%s",
                    item.getId(), item.getName(), toYuan(item.getPrice()), item.getStock(),
                    item.getBrand(), item.getCategory(), item.getSpec());
        } catch (Exception e) {
            log.error("查询商品详情失败，itemId={}", itemId, e);
            return "商品服务暂时不可用，请稍后再试。";
        }
    }

    private static String toYuan(Integer fen) {
        if (fen == null) {
            return "未知";
        }
        return new java.math.BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString();
    }
}
