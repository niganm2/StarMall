package com.hmall.item.es;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.domain.po.Item;
import com.hmall.item.domain.po.ItemDoc;
import com.hmall.item.service.IItemService;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

//@SpringBootTest(properties = "spring.profiles.active=local")
public class ElasticSearchTest {

    private RestHighLevelClient client;

    @Test
    void testMatchAll() throws IOException {
        //1.创建request对象
        SearchRequest request = new SearchRequest("items");
        //2.配置request
        request.source()
                .query(QueryBuilders.matchAllQuery());
        //3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.处理response,解析结果
        parseResponseResult(response);
    }

    @Test
    void testSearch() throws IOException {
        //1.创建request对象
        SearchRequest request = new SearchRequest("items");
        //2.配置request
        request.source().query(
                QueryBuilders.boolQuery()
                        .must(QueryBuilders.matchQuery("name", "脱脂牛奶"))
                        .filter(QueryBuilders.termQuery("brand.keyword", "德亚"))
                        .filter(QueryBuilders.rangeQuery("price").lt(30000))
        );
        //3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.处理response,解析结果
        parseResponseResult(response);
    }

    @Test
    void testSortAndPage() throws IOException {
        //0.模拟前端传递得分页参数
        int pageNo = 1, pageSize = 5;
        //1.创建request对象
        SearchRequest request = new SearchRequest("items");
        //2.1 query
        request.source().query(QueryBuilders.matchAllQuery());
        //2.2 分页
        request.source().from((pageNo - 1) * pageSize).size(pageSize);
        //2.3 排序
        request.source()
                .sort("sold", SortOrder.DESC)
                .sort("price", SortOrder.ASC);
        //3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.处理response,解析结果
        parseResponseResult(response);
    }

    @Test
    void testHighlight() throws IOException {
        //1.创建request对象
        SearchRequest request = new SearchRequest("items");
        //2.1 query
        request.source().query(QueryBuilders.matchQuery("name", "脱脂牛奶"));
        //2.2 highlight
        request.source().highlighter(
                SearchSourceBuilder.highlight()
                        .field("name")
                        .preTags("<em>")
                        .postTags("</em>")
        );
        //3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.处理response,解析结果
        parseResponseResult(response);
    }

    @Test
    void testAggregation() throws IOException {
        //1.创建request对象
        SearchRequest request = new SearchRequest("items");
        //2.配置request
        request.source().size(0);
        String brandName = "brandAgg";
        request.source().aggregation(
                AggregationBuilders
                        .terms(brandName)
                        .field("brand.keyword")
                        .size(10)
        );
        //3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.处理response,解析结果
        Aggregations aggregations = response.getAggregations();
        //4.1 根据聚合名称获取聚合结果
        Terms brandAgg = aggregations.get(brandName);
        //4.2 获取聚合结果
        List<? extends Terms.Bucket> buckets = brandAgg.getBuckets();
        //4.3 遍历
        for (Terms.Bucket bucket : buckets) {
            //4.4 获取桶得key
            String key = bucket.getKeyAsString();
            System.out.println("key:" + key);
            //4.5 获取桶中key的count
            long count = bucket.getDocCount();
            System.out.println("count:" + count);
        }
    }

    private static void parseResponseResult(SearchResponse response) {
        SearchHits searchHits = response.getHits();
        //4.1 总条数
        long total = searchHits.getTotalHits().value;
        System.out.println("总条数：" + total);
        //4.2 命中得数据
        SearchHit[] hits = searchHits.getHits();
        for (SearchHit hit : hits) {
            //4.3 获取数据
            String json = hit.getSourceAsString();
            //4.4 转换成Doc对象
            ItemDoc itemDoc = JSONUtil.toBean(json, ItemDoc.class);
            //4.5 获取高亮数据
            Map<String, HighlightField> hfs = hit.getHighlightFields();
            if (hfs != null && !hfs.isEmpty()) {
                //4.6 获取高亮字段
                HighlightField hf = hfs.get("name");
                //4.7 获取高亮字段值
                itemDoc.setName(hf.getFragments()[0].toString());
            }
            System.out.println("itemDoc:" + itemDoc);
        }
    }


    @BeforeEach
    void setUp() {
        client = new RestHighLevelClient(RestClient.builder(
                HttpHost.create("http://192.168.100.128:9200")
        ));
    }

    @AfterEach
    void tearDown() {
        try {
            client.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
