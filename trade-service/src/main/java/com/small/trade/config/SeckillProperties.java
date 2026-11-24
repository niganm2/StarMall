package com.small.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀活动配置：活动商品、秒杀价、秒杀库存。
 *
 * @author YL
 */
@Data
@Component
@ConfigurationProperties(prefix = "sm.seckill")
public class SeckillProperties {

    /**
     * 秒杀活动列表
     */
    private List<Activity> activities = new ArrayList<>();

    /**
     * 秒杀结果缓存时长（分钟）
     */
    private long resultTtlMinutes = 30;

    @Data
    public static class Activity {
        /**
         * 商品 id
         */
        private Long itemId;
        /**
         * 秒杀价（分）
         */
        private Integer seckillPrice;
        /**
         * 秒杀库存
         */
        private Integer stock;
    }
}
