package com.example.flashmall.common.util;

// 统一生成 key，避免硬编码字符串，方便维护和管理
public class RedisKeyUtil {
    private static final String PRODUCT_CACHE_KEY = "product:";
    private static final String SHOP_CACHE_KEY = "shop:";
    private static final String USER_CACHE_KEY = "user:";
    private static final String ORDER_CACHE_KEY = "order:";
    public static final String SHOP_KEY_PREFIX = "shop:";
    public static final long SHOP_TTL_BASE = 3600L; // 1小时基础TTL
    public static final long PRODUCT_TTL_BASE = 1800L; // 30分钟基础TTL
    public static final String SECKILL_STOCK_PREFIX = "seckill:stock:";
    public static final String SECKILL_EVENT_PREFIX = "seckill:event:";
    public static final String SECKILL_USER_PREFIX = "seckill:user:";

    public static String getProductKey(Long productId) {
        return PRODUCT_CACHE_KEY + productId;
    }
    public static String getShopKey(Long shopId) {
        return SHOP_CACHE_KEY + shopId;
    }
    public static String getUserKey(Long userId) {
        return USER_CACHE_KEY + userId;
    }
    public static String getOrderKey(Long orderId) {
        return ORDER_CACHE_KEY + orderId;
    }
    public static String buildSeckillStockKey(Long productId) {
        return SECKILL_STOCK_PREFIX + productId;
    }
    public static String buildSeckillEventKey(Long productId) {
        return SECKILL_EVENT_PREFIX + productId;
    }
    public static String buildSeckillUserKey(Long productId, Long userId) {
        return SECKILL_USER_PREFIX + productId + ":" + userId;
    }
}
