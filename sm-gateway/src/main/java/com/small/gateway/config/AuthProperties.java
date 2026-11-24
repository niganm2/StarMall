package com.small.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "sm.auth")
public class AuthProperties {
    private List<String> includePaths;
    private List<String> excludePaths;
    /**
     * 仅允许服务内部通过 Feign 直连调用的路径，网关对外一律拒绝，防止刷余额、退他人款
     */
    private List<String> denyPaths = new ArrayList<>(Arrays.asList(
            "/users/money/**",
            "/pay-orders/biz/**"
    ));
}
