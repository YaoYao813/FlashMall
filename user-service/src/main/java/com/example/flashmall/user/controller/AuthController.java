package com.example.flashmall.user.controller;

import com.example.flashmall.user.service.UserService;
import com.example.flashmall.user.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.flashmall.user.entity.User;
import javax.xml.transform.Result;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private UserService userService;

    // 注册
    @PostMapping("/register")
    public Result register(@RequestBody User user) {
        userService.register(user);
        return Result.success("注册成功");
    }

    // 登录
    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest request) {
        String token = userService.login(request.getUsername(), request.getPassword());
        return Result.success(token);
    }

    // 登出
    @PostMapping("/logout")
    public Result logout() {
        Long userId = UserContext.getUserId();
        userService.logout(userId);
        return Result.success("退出成功");
    }
}
