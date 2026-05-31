package com.example.flashmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.user.entity.User;
import com.example.flashmall.user.mapper.UserMapper;
import com.example.flashmall.user.service.UserService;
import com.example.flashmall.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;  // BCrypt 加密

    @Override
    public void register(User user) {
        // 1. 检查用户名是否已存在
        User existUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername())
        );
        if (existUser != null) {
            throw new RuntimeException("用户名已存在");
        }
        // 2. 密码加密
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        // 3. 保存
        userMapper.insert(user);
    }

    @Override
    public String login(String username, String password) {
        // 1. 查询用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }
        // 2. 校验密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        // 3. 生成 JWT
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        // 4. 存入 Redis（7 天过期，与 JWT 保持一致）
        String redisKey = "token:user:" + user.getId();
        redisTemplate.opsForValue().set(redisKey, token, 7, TimeUnit.DAYS);
        return token;
    }

    @Override
    public void logout(Long userId) {
        redisTemplate.delete("token:user:" + userId);
    }
}
