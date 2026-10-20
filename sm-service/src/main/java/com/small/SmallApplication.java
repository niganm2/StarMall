package com.small;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.small.mapper")
@SpringBootApplication
public class SmallApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmallApplication.class, args);
    }
}