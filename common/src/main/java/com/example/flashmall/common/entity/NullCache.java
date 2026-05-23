package com.example.flashmall.common.entity;

import java.io.Serializable;

public class NullCache implements Serializable {
    // 固定标记，方便识别
    private final String cacheType = "NULL_CACHE";

    public NullCache() {
    }

    public String getCacheType() {
        return cacheType;
    }

    // 提供一个常量实例，方便代码中直接引用（也可每次 new）
    // NullCache.INSTANCE 即为一个全局共享的空缓存对象，节省内存开销。
    public static final NullCache INSTANCE = new NullCache();
}
