package com.example.flashmall.user.service;

import com.example.flashmall.user.entity.User;

public interface UserService {
    void register(User user);
    String login(String username, String password);
    void logout(Long userId);
}
