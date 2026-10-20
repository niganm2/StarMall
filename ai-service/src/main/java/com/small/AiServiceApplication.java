package com.small;

import com.small.api.config.DefaultFeignConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 星购商城 AI 智能助手服务启动类。
 * 独立技术栈：Spring Boot 3.4 + Java 17 + Spring AI 1.0.0 + 阿里百炼 DashScope。
 */
@EnableFeignClients(basePackages = "com.small.api.client", defaultConfiguration = DefaultFeignConfig.class)
@SpringBootApplication
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
