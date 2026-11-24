package com.small.item.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.small.api.dto.ItemDTO;
import com.small.common.domain.PageDTO;
import com.small.item.domain.po.ItemDoc;
import com.small.item.domain.query.ItemPageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemSearchService {

    private static final String INDEX = "items";

    /** 热词搜索结果缓存：前 3 页缓存 60 秒 */
    private static final String SEARCH_CACHE_PREFIX = "item:search:";
    private static final Duration SEARCH_CACHE_TTL = Duration.ofSeconds(60);
    private static final int HOT_SEARCH_PAGE_MAX = 3;

    private final RestHighLevelClient esClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public PageDTO<ItemDTO> search(ItemPageQuery query) throws Exception {
        // 热词搜索的前几页优先读缓存，避免同一个热点查询反复打 ES
        String cacheKey = null;
        if (query.getPageNo() != null && query.getPageNo() <= HOT_SEARCH_PAGE_MAX) {
            cacheKey = searchCacheKey(query);
            PageDTO<ItemDTO> cached = readSearchCache(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(buildQuery(query, true))
                .from(query.from())
                .size(query.getPageSize())
                .trackTotalHits(true);
        applySort(source, query);

        SearchResponse response = esClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        List<ItemDTO> list = new ArrayList<>();
        for (SearchHit hit : response.getHits()) {
            list.add(toDTO(JSONUtil.toBean(hit.getSourceAsString(), ItemDoc.class)));
        }
        long total = response.getHits().getTotalHits().value;
        long pages = (total + query.getPageSize() - 1) / query.getPageSize();
        PageDTO<ItemDTO> result = new PageDTO<>(total, pages, list);
        if (cacheKey != null) {
            writeSearchCache(cacheKey, result);
        }
        return result;
    }

    private String searchCacheKey(ItemPageQuery query) {
        return SEARCH_CACHE_PREFIX + StrUtil.blankToDefault(query.getKey(), "_")
                + ":" + query.getPageNo()
                + ":" + query.getPageSize()
                + ":" + StrUtil.blankToDefault(query.getSortBy(), "_")
                + ":" + query.getIsAsc()
                + ":" + query.getMinPrice()
                + ":" + query.getMaxPrice()
                + ":" + StrUtil.blankToDefault(query.getBrand(), "_")
                + ":" + StrUtil.blankToDefault(query.getCategory(), "_");
    }

    private PageDTO<ItemDTO> readSearchCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<PageDTO<ItemDTO>>() {
            });
        } catch (Exception e) {
            // 缓存异常时降级为直接查 ES，不影响功能
            log.warn("读取搜索缓存失败：{}", e.getMessage());
            return null;
        }
    }

    private void writeSearchCache(String key, PageDTO<ItemDTO> page) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(page), SEARCH_CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入搜索缓存失败：{}", e.getMessage());
        }
    }

    public Map<String, List<String>> filters(ItemPageQuery query) throws Exception {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(0)
                // 品牌、分类作为过滤条件时不再参与聚合，避免只剩已选中的一项
                .query(buildQuery(query, false))
                .aggregation(AggregationBuilders.terms("brands").field("brand.keyword").size(60))
                .aggregation(AggregationBuilders.terms("categories").field("category.keyword").size(60));

        SearchResponse response = esClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("brand", terms(response, "brands"));
        result.put("category", terms(response, "categories"));
        return result;
    }

    public List<String> suggestion(String key) throws Exception {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(QueryBuilders.matchQuery("name", key).operator(Operator.OR))
                .fetchSource(new String[]{"name"}, null)
                .size(20);
        SearchResponse response = esClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        return Arrays.stream(response.getHits().getHits())
                .map(hit -> JSONUtil.toBean(hit.getSourceAsString(), ItemDoc.class).getName())
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<String> categories() throws Exception {
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(0)
                .query(QueryBuilders.termQuery("status", 1))
                .aggregation(AggregationBuilders.terms("categories").field("category.keyword").size(100));
        SearchResponse response = esClient.search(new SearchRequest(INDEX).source(source), RequestOptions.DEFAULT);
        return terms(response, "categories");
    }

    private QueryBuilder buildQuery(ItemPageQuery query, boolean withFacetFilters) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (StrUtil.isNotBlank(query.getKey())) {
            bool.must(QueryBuilders.multiMatchQuery(query.getKey())
                    .field("name", 3.0f)
                    .field("brand")
                    .field("category")
                    .operator(Operator.AND));
            // 标题中完整包含关键字的商品优先，保证"小米17"这类型号检索的准确度
            bool.should(QueryBuilders.matchPhraseQuery("name", query.getKey()).boost(5.0f));
        }
        if (withFacetFilters) {
            if (StrUtil.isNotBlank(query.getBrand())) {
                bool.filter(QueryBuilders.termQuery("brand.keyword", query.getBrand()));
            }
            if (StrUtil.isNotBlank(query.getCategory())) {
                bool.filter(QueryBuilders.termQuery("category.keyword", query.getCategory()));
            }
        }
        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            RangeQueryBuilder range = QueryBuilders.rangeQuery("price");
            if (query.getMinPrice() != null) {
                range.gte(query.getMinPrice());
            }
            if (query.getMaxPrice() != null && query.getMaxPrice() > 0) {
                range.lte(query.getMaxPrice());
            }
            bool.filter(range);
        }
        bool.filter(QueryBuilders.termQuery("status", 1));
        return bool;
    }

    private void applySort(SearchSourceBuilder source, ItemPageQuery query) {
        String sortBy = query.getSortBy();
        boolean asc = Boolean.TRUE.equals(query.getIsAsc());
        if (StrUtil.isBlank(sortBy)) {
            if (StrUtil.isNotBlank(query.getKey())) {
                // 有关键字时按相关度排序
                source.sort(SortBuilders.scoreSort().order(SortOrder.DESC));
            } else {
                // 无关键字时按销量倒序
                source.sort(SortBuilders.fieldSort("sold").order(SortOrder.DESC));
            }
            return;
        }
        String field;
        switch (sortBy) {
            case "price":
                field = "price";
                break;
            case "sold":
                field = "sold";
                break;
            default:
                field = "updateTime";
        }
        source.sort(SortBuilders.fieldSort(field).order(asc ? SortOrder.ASC : SortOrder.DESC));
    }

    private List<String> terms(SearchResponse response, String name) {
        Terms terms = response.getAggregations().get(name);
        if (terms == null) {
            return new ArrayList<>();
        }
        return terms.getBuckets().stream()
                .map(Terms.Bucket::getKeyAsString)
                .filter(StrUtil::isNotBlank)
                .sorted()
                .collect(Collectors.toList());
    }

    private ItemDTO toDTO(ItemDoc doc) {
        ItemDTO dto = new ItemDTO();
        dto.setId(StrUtil.isBlank(doc.getId()) ? null : Long.valueOf(doc.getId()));
        dto.setName(doc.getName());
        dto.setPrice(doc.getPrice());
        dto.setStock(doc.getStock());
        dto.setImage(doc.getImage());
        dto.setCategory(doc.getCategory());
        dto.setBrand(doc.getBrand());
        dto.setSpec(doc.getSpec());
        dto.setSold(doc.getSold());
        dto.setCommentCount(doc.getCommentCount());
        dto.setIsAD(doc.getIsAD());
        dto.setStatus(doc.getStatus());
        return dto;
    }
}
