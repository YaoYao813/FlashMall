package com.example.flashmall.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class BaseEntity implements Serializable {

    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    // Getters and Setters
    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
