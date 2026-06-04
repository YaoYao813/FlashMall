# FlashMall 闪电秒杀商城

基于 Spring Cloud Alibaba 微服务架构的高并发秒杀系统，10个容器一键启动。

## 架构图

```
┌────────────────────────────────────────────────────────────────┐
│                        用户 / 浏览器                            │
└─────────────────────────────┬──────────────────────────────────┘
                              │  http://localhost:8888
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Gateway (API 网关 :8888)                       │
│          Spring Cloud Gateway + JWT 鉴权 + Sentinel 流控          │
└──────┬──────────┬──────────┬──────────┬─────────────────────────┘
       │          │          │          │
       ▼          ▼          ▼          ▼
┌──────────┐┌──────────┐┌──────────┐┌──────────────┐
│  user-   ││  shop-   ││  order-  ││  seckill-    │
│  service ││  service ││  service ││  service     │
│  :8081   ││  :8082   ││  :8083   ││  :8080       │
│  用户服务 ││  商品服务 ││  订单服务 ││  秒杀服务     │
│  ┌─────┐ ││  ┌─────┐ ││  ┌─────┐ ││  ┌────────┐ │
│  │ JWT  │ ││  │布隆  │ ││  │延迟  │ ││  │Lua脚本 │ │
│  │ 鉴权  │ ││  │过滤器│ ││  │消息  │ ││  │库存预减│ │
│  └─────┘ ││  └─────┘ ││  └─────┘ ││  │Semaphore│ │
│          ││          ││          ││  │限流     │ │
└──────┬───┘└────┬─────┘└────┬─────┘└──────┬───────┘
       │         │         │         │
       └─────────┼─────────┼─────────┘
                 │         │
     ┌───────────▼──┐  ┌──▼──────────┐  ┌──────────────────┐
     │    MySQL     │  │    Redis    │  │    RabbitMQ      │
     │    :3308     │  │    :6380    │  │    :5672         │
     │   8.0        │  │   7-alpine  │  │   3.12-mgmt      │
     │   (主机端口)   │  │   (主机端口)  │  │   (延迟消息插件)   │
     └──────────────┘  └─────────────┘  └──────────────────┘

     ┌──────────────┐  ┌──────────────────┐
     │    Nacos     │  │    Sentinel      │
     │    :8848     │  │    Dashboard     │
     │   v2.2.3     │  │    :8858         │
     │  服务注册/配置 │  │    流控/熔断面板   │
     └──────────────┘  └──────────────────┘
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7 + Spring Cloud 2021 + Spring Cloud Alibaba 2021 |
| 注册中心 | Nacos 2.2.3 |
| 网关 | Spring Cloud Gateway（WebFlux 响应式） |
| 数据库 | MySQL 8.0 + MyBatis-Plus |
| 缓存 | Redis 7 + Redisson 分布式锁 |
| 消息队列 | RabbitMQ 3.12（延迟消息插件） |
| 流控/熔断 | Sentinel 1.8.6 |
| 认证 | JWT（jjwt 0.9.1） |
| 密码加密 | BCrypt（strength 10） |
| 文档 | SpringDoc（Swagger） |
| 容器化 | Docker + Docker Compose |
| 测试 | JUnit 5 + Mockito |

## 秒杀核心链路

```
请求进入 → Semaphore限流(200并发) → Redis Lua脚本预减库存
         → 返回订单号(异步) → MQ削峰 → 批量消费(50条/批)
         → Redisson分布式锁 → 乐观锁扣减MySQL → 延迟消息自动取消
```

### 防超卖方案
- **Redis Lua 原子预减**：在 Redis 中预扣库存，Lua 脚本保证原子性
- **MySQL 乐观锁**：`UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock >= ?`
- **分布式锁**：Redisson 对同一商品加锁，串行化库存扣减
- **布隆过滤器**：预先过滤不存在的商品 ID

## 快速启动

### 前置条件

- Docker Desktop（含 Docker Compose）
- 至少 4GB 可用内存

### 一键启动

```bash
# 1. 克隆项目
git clone <仓库地址>
cd FlashMall

# 2. 下载 Sentinel Dashboard JAR（仅首次）
cd sentinel && bash download.sh && cd ..

# 3. 启动所有服务（首次构建约 5 分钟）
docker compose up -d --build

# 4. 查看启动状态
docker compose ps
```

### 验证

```bash
# 注册用户
curl -X POST http://localhost:8888/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 登录（获取 Token）
curl -X POST http://localhost:8888/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testbuyer","password":"123456"}'

# 查询商品（需要 Token）
curl http://localhost:8888/api/product \
  -H "Authorization: Bearer <TOKEN>"

# 秒杀下单
curl -X POST "http://localhost:8888/api/seckill?productId=1" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-User-Id: 1"
```

### 管理界面

| 服务 | 地址 | 账号 |
|------|------|------|
| Nacos | http://localhost:8848/nacos | nacos / nacos |
| RabbitMQ | http://localhost:15672 | admin / 123456 |
| Sentinel | http://localhost:8858 | sentinel / sentinel |

### 各个微服务的 Swagger

| 服务 | Swagger UI |
|------|------------|
| 秒杀服务 | http://localhost:8888/api/seckill/swagger-ui.html |
| 用户服务 | http://localhost:8081/swagger-ui.html |
| 订单服务 | http://localhost:8083/swagger-ui.html |

## 项目结构

```
FlashMall/
├── common/                    # 公共模块（实体、工具、异常处理）
├── gateway/                   # API 网关 (Spring Cloud Gateway)
├── user-service/              # 用户服务（注册、登录、地址管理）
├── shop-service/              # 店铺/商品服务
├── order-service/             # 订单服务（创建、支付、延迟取消）
├── seckill-service/           # 秒杀服务（Lua脚本、MQ削峰、乐观锁）
├── mysql/init/                # 数据库初始化脚本（首次启动自动执行）
│   ├── 01-schema.sql          # 建表 DDL
│   └── 02-seed-data.sql       # 种子数据
├── rabbitmq/                  # RabbitMQ 定制镜像（含延迟消息插件）
├── sentinel/                  # Sentinel Dashboard 镜像
├── Dockerfile                 # 多阶段构建（Maven 编译 → JRE 运行）
├── docker-compose.yml         # 10 个容器的编排
└── pom.xml                    # 父 POM（依赖版本管理）
```

## 数据库表

| 表名 | 说明 |
|------|------|
| `user` | 用户表（BCrypt 加密） |
| `shop` | 店铺表 |
| `product` | 商品表 |
| `product_stock` | 秒杀库存表（含乐观锁版本号） |
| `seckill_event` | 秒杀活动表 |
| `address` | 收货地址表 |
| `order` | 订单表 |

## 环境变量

所有服务通过 Docker 环境变量配置，无需修改配置文件。详见 `docker-compose.yml`。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NACOS_ADDR` | nacos:8848 | Nacos 地址 |
| `DB_HOST` | mysql | 数据库主机 |
| `DB_PASSWORD` | root123 | 数据库密码 |
| `REDIS_HOST` | redis | Redis 主机 |
| `RABBITMQ_HOST` | rabbitmq | RabbitMQ 主机 |
| `JWT_SECRET` | flashmall-secret-key-2024 | JWT 签名密钥 |
