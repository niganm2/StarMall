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
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

@SpringBootTest(properties = "spring.profiles.active=local")
public class ElasticDocumentTest {

    private RestHighLevelClient client;
    @Autowired
    private IItemService itemService;

    @Test
    void testIndexDoc() throws IOException {
        //0.准备文档数据
        Item item = itemService.getById(100000011127L);  //item对象
        ItemDoc itemDoc = BeanUtils.copyProperties(item, ItemDoc.class);
        // 1. 准备request对象
        IndexRequest request = new IndexRequest("items").id(item.getId().toString());
        //2.准备请求参数
        request.source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON);
        //3.发送请求
        client.index(request, RequestOptions.DEFAULT);
    }


    @Test
    void testGetDoc() throws IOException {
        //1.准备request对象
        GetRequest request = new GetRequest("items", "100000011127");
        //2.发送请求
        GetResponse getResponse = client.get(request, RequestOptions.DEFAULT);
        //3.处理响应结果
        String json = getResponse.getSourceAsString();
        JSONUtil.toBean(json, ItemDoc.class);
        System.out.println("json:"+json);
    }

    @Test
    void testDeleteDoc() throws IOException {
        //1.准备request对象
        DeleteRequest request = new DeleteRequest("items", "100000011127");
        //2.发送请求
        client.delete(request, RequestOptions.DEFAULT);
    }

    @Test
    void testUpdateDoc() throws IOException {
        //1.准备request对象
        UpdateRequest request = new UpdateRequest("items", "100000011127");
        //2.准备请求参数
        request.doc(
                "price",25000
        );
        //2.发送请求
        client.update(request, RequestOptions.DEFAULT);
    }

    //批处理
    @Test
    void testBulkDoc() throws IOException {
        int pageNo = 1, pageSize = 500;
       while(true){
           //0.准备文档数据
           Page<Item> page = itemService.lambdaQuery()
                   .eq(Item::getStatus, 1)
                   .page(Page.of(pageNo, pageSize));
           List<Item> records = page.getRecords();
           if (records.isEmpty() || records ==null){
               return;
           }
           // 1. 准备request对象
           BulkRequest request = new BulkRequest();
           //2.准备请求参数
           for (Item item : records){
               request.add(new IndexRequest("items")
                       .id(item.getId().toString())
                       .source(JSONUtil.toJsonStr(BeanUtils.copyProperties(item, ItemDoc.class)),XContentType.JSON));
           }
           //3.发送请求
           client.bulk(request, RequestOptions.DEFAULT);
           //4.翻页
           pageNo++;
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
