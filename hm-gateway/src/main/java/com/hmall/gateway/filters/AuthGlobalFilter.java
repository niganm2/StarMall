package com.hmall.gateway.filters;

import cn.hutool.core.text.AntPathMatcher;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;

    private final JwtTool jwtTool;

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.获取request
        ServerHttpRequest request = exchange.getRequest();
        //2.判断是否需要拦截
        if (isExclude(request.getPath().toString())){
            //放行
            return chain.filter(exchange);
        }
        //3.获取token
        String token = null;
        List<String> headers = request.getHeaders().get("authorization");
        if (headers != null && !headers.isEmpty()){
            token = headers.get(0);
        }
        //4.检验并解析token
        Long userId = null;
        try {
            userId = jwtTool.parseToken(token);
        } catch (Exception e) {
            //拦截，并设置响应码401
            //创建响应对象
            ServerHttpResponse response = exchange.getResponse();
            //设置响应码401
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            //终止请求
            return response.setComplete();
        }
        //5.传递用户信息
        String userInfo = userId.toString();
        ServerWebExchange swe = exchange.mutate()
                .request(builder -> builder.header("user-info", userInfo))
                .build();
        //6.放行 并且把swe传递给下一个过滤器
        return chain.filter(swe);
    }

    //判断是否需要拦截
    private boolean isExclude(String path) {
        //遍历排除路径
        for (String pathPattern : authProperties.getExcludePaths()){
            //判断是否匹配
            if (antPathMatcher.match(pathPattern,path)){
                //匹配
                return true;
            }
        }
        //不匹配
        return false;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
