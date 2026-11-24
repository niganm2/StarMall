package com.small.item.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EsConfig {

    @Value("${sm.es.host:127.0.0.1}")
    private String host;

    @Value("${sm.es.port:9200}")
    private int port;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));
    }
}
