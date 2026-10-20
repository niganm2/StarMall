package com.small.item.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Collections;

/**
 * Sentinel 规则配置：应用启动时以代码方式加载限流与熔断降级规则。
 * <p>
 * 商品查询是典型的"读多写少"接口，在大促、秒杀等流量洪峰下最容易被打垮，
 * 因此对它同时施加两道保护：QPS 限流（超额快速失败）+ 慢调用熔断（防止被慢请求拖死）。
 *
 * @author YL
 */
@Slf4j
@Configuration
public class SentinelRuleConfig {

    /**
     * 商品查询资源名，与 ItemController 上 @SentinelResource 的 value 保持一致
     */
    public static final String ITEM_QUERY_RESOURCE = "item:queryByIds";

    /**
     * 商品查询接口 QPS 上限
     */
    private static final double QPS_LIMIT = 50D;

    /**
     * 慢调用判定阈值（毫秒）
     */
    private static final double SLOW_RT_MILLIS = 500D;

    @PostConstruct
    public void initRules() {
        // 1.流控规则：单机 QPS 超过 50 的请求快速失败，避免流量全部压到数据库
        FlowRule flowRule = new FlowRule();
        flowRule.setResource(ITEM_QUERY_RESOURCE);
        flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flowRule.setCount(QPS_LIMIT);
        flowRule.setLimitApp("default");
        FlowRuleManager.loadRules(Collections.singletonList(flowRule));

        // 2.熔断降级规则：统计窗口内平均响应时间超过 500ms 即熔断 10 秒，期间请求直接走兜底逻辑
        DegradeRule degradeRule = new DegradeRule();
        degradeRule.setResource(ITEM_QUERY_RESOURCE);
        degradeRule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        degradeRule.setCount(SLOW_RT_MILLIS);
        degradeRule.setTimeWindow(10);
        degradeRule.setMinRequestAmount(5);
        degradeRule.setStatIntervalMs(1000);
        DegradeRuleManager.loadRules(Collections.singletonList(degradeRule));

        log.info("Sentinel 规则加载完成：资源={}, QPS限流={}, 慢调用熔断阈值={}ms",
                ITEM_QUERY_RESOURCE, QPS_LIMIT, SLOW_RT_MILLIS);
    }
}
