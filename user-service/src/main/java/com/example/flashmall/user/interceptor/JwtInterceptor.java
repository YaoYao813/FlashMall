package com.example.flashmall.user.interceptor;
// Jwt拦截器

import com.example.flashmall.user.util.JwtUtil;
import com.example.flashmall.user.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class JwtInterceptor implements HandlerInterceptor {
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 从 Header 中获取 Token
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            response.setStatus(401);
            response.getWriter().write("未登录，请先登录");
            return false;
        }
        token = token.substring(7);  // 去掉 "Bearer "
        // 2. 校验 JWT 是否有效
        if (!jwtUtil.validateToken(token)) {
            response.setStatus(401);
            response.getWriter().write("Token 已过期，请重新登录");
            return false;
        }
        // 3. 检查 Redis 中是否存在（防止用户登出后被使用）
        Long userId = jwtUtil.getUserIdFromToken(token);
        String redisKey = "token:user:" + userId;
        String redisToken = (String) redisTemplate.opsForValue().get(redisKey);
        if (redisToken == null || !redisToken.equals(token)) {
            response.setStatus(401);
            response.getWriter().write("Token 已失效，请重新登录");
            return false;
        }
        // 4. 将用户信息存入 ThreadLocal（当前线程共享）
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清除，防止内存泄漏
        UserContext.clear();
    }
}
