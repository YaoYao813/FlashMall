package com.example.flashmall.shop.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 分布式锁及客户端配置
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.mode:single}")
    private String mode;

    @Value("${redisson.address:redis://127.0.0.1:6379}")
    private String address;

    @Value("${redisson.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        if ("cluster".equalsIgnoreCase(mode)) {
            ClusterServersConfig clusterConfig = config.useClusterServers();
            // address can be comma separated
            String[] addrs = address.split(",");
            for (String a : addrs) {
                clusterConfig.addNodeAddress(a.trim());
            }
            if (password != null && password.length() > 0) {
                clusterConfig.setPassword(password);
            }
        } else {
            SingleServerConfig single = config.useSingleServer();
            single.setAddress(address);
            if (password != null && password.length() > 0) {
                single.setPassword(password);
            }
        }
        return Redisson.create(config);
    }
}

