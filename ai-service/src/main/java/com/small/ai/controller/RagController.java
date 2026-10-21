package com.small.ai.controller;

import com.small.ai.rag.ProductIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品向量索引管理接口：用于手动重建索引与查看索引状态。
 */
@Slf4j
@RestController
@RequestMapping("/ai/rag")
@RequiredArgsConstructor
public class RagController {

    private final ProductIndexService productIndexService;

    /**
     * 全量重建商品向量索引（商品数据变更后可手动触发）。
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        ProductIndexService.SyncResult result = productIndexService.rebuild();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("indexed", result.indexed());
        body.put("time", result.time());
        return body;
    }

    /**
     * 语义检索自测：直接查看某段需求描述命中的商品，便于验证索引效果。
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("query") String query) {
        List<Map<String, Object>> hits = productIndexService.search(query, 5).stream()
                .map(document -> {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("itemId", document.getMetadata().get("itemId"));
                    hit.put("name", document.getMetadata().get("name"));
                    hit.put("score", document.getScore());
                    return hit;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("hits", hits);
        return body;
    }

    /**
     * 删除向量集合（建集合参数变更后使用，删除后需重启应用）。
     */
    @DeleteMapping("/collection")
    public Map<String, Object> dropCollection() {
        productIndexService.dropCollection();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dropped", true);
        return body;
    }

    /**
     * 查询索引状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", productIndexService.getVectorCount());
        body.put("lastSyncTime", productIndexService.getLastSyncTime());
        return body;
    }
}
