package com.example.flashmall.user.controller;

import com.example.flashmall.common.util.Result;
import com.example.flashmall.user.entity.User;
import com.example.flashmall.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result register(@Valid @RequestBody User user) {
        userService.register(user);
        return Result.success(null, "注册成功");
    }

    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginRequest request) {
        String token = userService.login(request.getUsername(), request.getPassword());
        return Result.success(token);
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader("X-User-Id") Long userId) {
        userService.logout(userId);
        return Result.success(null, "退出成功");
    }
}
