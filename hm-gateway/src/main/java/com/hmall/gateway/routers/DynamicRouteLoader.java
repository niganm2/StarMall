package com.hmall.gateway.routers;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

@Component
@Slf4j
@RequiredArgsConstructor
public class DynamicRouteLoader {

    private final NacosConfigManager nacosConfigManager;

    private final RouteDefinitionWriter routeDefinitionWriter;

    private final Set<String> routeIds = new HashSet<>();

    private final String dataId = "gateway-routes.json";
    private final String group = "DEFAULT_GROUP";

    @PostConstruct
    public void initRouteConfig() throws Exception {
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(dataId, group, 5000, new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        try {
                            updateConfigInfo(configInfo);
                        } catch (NacosException e) {
                            log.error("更新路由配置失败", e);
                        }
                    }
                });
        if (configInfo != null && !configInfo.isEmpty()) {
            updateConfigInfo(configInfo);
        }
    }

    public void updateConfigInfo(String configInfo) throws NacosException {
        log.info("监听到路由配置信息：{}", configInfo);
        //1.解析配置信息，转为RouteDefinition
        List<RouteDefinition> routeDefinitions = JSONUtil.toList(configInfo, RouteDefinition.class);
        //删除旧路由表
        for (String routeId : routeIds){
            routeDefinitionWriter.delete(Mono.just(routeId)).subscribe();
        }
        //清空旧路由表
        routeIds.clear();
        //2.更新路由
        for (RouteDefinition routeDefinition : routeDefinitions) {
            //2.1.更新路由表
            routeDefinitionWriter.save(Mono.just(routeDefinition)).subscribe();
            //2.2.记录路由id，便于删除
            routeIds.add(routeDefinition.getId());
        }
    }
}
