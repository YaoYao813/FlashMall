package com.example.flashmall.gateway.filter;

import com.example.flashmall.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class GlobalJwtFilter implements GlobalFilter, Ordered {

    /** 白名单：不需要认证的路径 **/
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/auth/register", //注册
            "/api/auth/login", //登录
            "/api/pay/callback" //支付回调
    );

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 全局过滤器 **/
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单直接放行
        if (isWhitePath(path)) {
            return chain.filter(exchange);
        }

        // 校验 JWT
        String token = extractToken(exchange.getRequest());
        if (token == null) {
            return unauthorized(exchange, "未登录，请先登录");
        }

        if (!jwtUtil.validateToken(token)) {
            log.warn("JWT 校验失败 path={} token={}", path, token);
            return unauthorized(exchange, "Token 已过期，请重新登录");
        }

        // 校验 Redis 中 token 是否有效（防止登出后继续使用）
        Long userId = jwtUtil.getUserIdFromToken(token);
        String redisKey = "token:user:" + userId;
        Boolean exists = redisTemplate.hasKey(redisKey);
        if (Boolean.FALSE.equals(exists)) {
            log.warn("Redis token 已失效 path={} userId={}", path, userId);
            return unauthorized(exchange, "Token 已失效，请重新登录");
        }

        // 将 userId 透传给下游服务
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private boolean isWhitePath(String path) {
        return WHITE_LIST.stream().anyMatch(path::equals);
    }

    // 抽离出请求头和token
    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
