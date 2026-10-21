package com.small.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 导购场景的 RAG 检索工具：把自然语言需求交给向量库召回商品。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRagTools {

    private static final int TOP_K = 5;

    private final ProductIndexService productIndexService;

    @Tool(description = "按自然语言描述语义检索商品（向量相似度检索）。适用于无法用关键字精确表达的模糊需求，例如『拍照好的手机』『3000元左右适合送人的礼物』『适合学生党的平价耳机』『家里装修要用的东西』；当 searchItems 用关键字或分类搜不到合适商品时，也应调用本工具。返回商品 id 与名称")
    public List<String> semanticSearchItems(
            @ToolParam(description = "用户的自然语言需求描述，尽量保留用户的原始表述与偏好") String query) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptyList();
        }
        try {
            List<Document> documents = productIndexService.search(query.trim(), TOP_K);
            log.debug("商品语义检索命中 {} 条，query={}", documents.size(), query);
            if (documents.isEmpty()) {
                return Collections.emptyList();
            }
            // 只返回 id 与名称，减少上下文占用；需要价格等详情时由模型再调 getItemById
            return documents.stream()
                    .map(document -> String.format("id：%s，名称：%s",
                            document.getMetadata().get("itemId"), document.getMetadata().get("name")))
                    .toList();
        } catch (Exception e) {
            log.error("语义检索商品失败，query={}", query, e);
            return Collections.emptyList();
        }
    }
}
