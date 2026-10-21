package com.small.ai.rag;

import com.small.api.client.ItemClient;
import com.small.api.dto.ItemDTO;
import com.small.api.dto.PageResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品向量索引服务（RAG 的离线入库侧）。
 * 通过 OpenFeign 从商品服务分页拉取在售商品，拼成文本后向量化写入 Milvus，
 * 供导购场景做自然语言语义检索；不直连业务库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexService {

    /** 每页拉取的商品数 */
    private static final int PAGE_SIZE = 100;
    /** 最多拉取的页数，防止异常数据导致死循环 */
    private static final int MAX_PAGES = 50;
    /** DashScope text-embedding-v3 单次请求最多 10 条文本 */
    private static final int EMBED_BATCH_SIZE = 10;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ItemClient itemClient;
    private final VectorStore vectorStore;
    private final MilvusServiceClient milvusClient;

    @Value("${spring.ai.vectorstore.milvus.collection-name:starmall_items}")
    private String collectionName;

    private volatile int indexedCount;
    private volatile String lastSyncTime = "";

    /**
     * 重建全量商品索引：先删同 id 的旧向量，再分批向量化写入，避免重复主键。
     */
    public SyncResult rebuild() {
        List<ItemDTO> items = fetchAllItems();
        if (items.isEmpty()) {
            log.warn("商品索引重建中止：商品服务未返回任何在售商品");
            return new SyncResult(0, lastSyncTime);
        }

        List<Document> documents = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            if (item.getId() != null) {
                documents.add(toDocument(item));
            }
        }
        // 先按确定的 id 删除旧向量，保证重复重建不会产生重复记录
        vectorStore.delete(documents.stream().map(Document::getId).toList());

        int added = 0;
        for (int start = 0; start < documents.size(); start += EMBED_BATCH_SIZE) {
            List<Document> batch = documents.subList(start, Math.min(start + EMBED_BATCH_SIZE, documents.size()));
            vectorStore.add(batch);
            added += batch.size();
            log.debug("商品向量写入进度：{}/{}", added, documents.size());
        }

        indexedCount = added;
        lastSyncTime = TIME_FORMATTER.format(LocalDateTime.now());
        log.info("商品向量索引重建完成，共 {} 条，时间 {}", indexedCount, lastSyncTime);
        return new SyncResult(indexedCount, lastSyncTime);
    }

    /**
     * 语义检索：按自然语言描述召回最相似的商品文档。
     */
    public List<Document> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        return documents == null ? List.of() : documents;
    }

    /**
     * 查询集合实际向量条数：走 Milvus 统计，重启后依然准确（不依赖内存计数）。
     */
    public long getVectorCount() {
        R<GetCollectionStatisticsResponse> response = milvusClient.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder().withCollectionName(collectionName).build());
        if (response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            log.warn("查询向量集合统计失败：{}", response.getMessage());
            return 0L;
        }
        return response.getData().getStatsList().stream()
                .filter(stat -> "row_count".equals(stat.getKey()))
                .mapToLong(stat -> {
                    try {
                        return Long.parseLong(stat.getValue());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .findFirst()
                .orElse(0L);
    }

    public String getLastSyncTime() {
        return lastSyncTime;
    }

    /**
     * 删除整个向量集合：索引类型/维度等建集合参数变更后使用，删除后需重启应用让其按新参数重建。
     */
    public void dropCollection() {
        R<RpcStatus> result = milvusClient.dropCollection(
                DropCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("删除向量集合失败：" + result.getMessage());
        }
        indexedCount = 0;
        lastSyncTime = "";
        log.info("已删除向量集合 {}", collectionName);
    }

    /**
     * 分页拉取全部在售商品，直到取完或达到页数上限。
     */
    private List<ItemDTO> fetchAllItems() {
        List<ItemDTO> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            PageResult<ItemDTO> page = itemClient.searchItems(null, null, null, null, null, pageNo, PAGE_SIZE);
            if (page == null || page.getList() == null || page.getList().isEmpty()) {
                break;
            }
            all.addAll(page.getList());
            Long pages = page.getPages();
            if (pages != null && pageNo >= pages) {
                break;
            }
        }
        return all;
    }

    /**
     * 把商品拼成适合语义检索的文本：名称/品牌/分类/规格是用户描述的主要依据。
     */
    private Document toDocument(ItemDTO item) {
        String text = String.format("商品名称：%s；品牌：%s；分类：%s；价格：%s元；规格：%s",
                text(item.getName()), text(item.getBrand()), text(item.getCategory()),
                toYuan(item.getPrice()), text(item.getSpec()));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("itemId", item.getId());
        metadata.put("name", text(item.getName()));
        metadata.put("price", item.getPrice() == null ? 0 : item.getPrice());
        metadata.put("category", text(item.getCategory()));
        metadata.put("brand", text(item.getBrand()));

        return Document.builder()
                .id("item-" + item.getId())
                .text(text)
                .metadata(metadata)
                .build();
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }

    private static String toYuan(Integer fen) {
        if (fen == null) {
            return "未知";
        }
        return new BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    /** 索引重建结果 */
    public record SyncResult(int indexed, String time) {
    }
}
