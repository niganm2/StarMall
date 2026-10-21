# StarMall 星购商城

基于 Spring Cloud 微服务架构的电商平台，包含完整的商品、购物车、订单、支付链路，并集成了基于 Spring AI 的智能购物助手（流式对话 + 工具调用 + RAG 语义检索）。

## 技术栈

| 模块 | 技术 |
|---|---|
| 业务微服务 | Spring Boot 2.7.12 + JDK 11 + Spring Cloud Alibaba（Nacos / Sentinel / Seata）+ OpenFeign + MyBatis-Plus + RabbitMQ + Redis |
| AI 服务 | Spring Boot 3.4.5 + **JDK 17** + Spring AI 1.0.0（spring-ai-alibaba-starter，直连 DashScope / 通义千问）+ RAG（text-embedding-v3 + Milvus） |
| 网关 | Spring Cloud Gateway（WebFlux）+ JWT 鉴权 |
| 前端 | 静态 HTML + Vue 2.5.16 + axios，nginx 部署并反向代理 `/api/**` |
| 基础设施 | MySQL 8、Nacos（注册中心 + 配置中心）、Seata（分布式事务）、RabbitMQ（含延迟队列）、Redis、Sentinel、Milvus（向量库） |

## 模块结构

```
StarMall
├── sm-common        公共依赖：异常处理、MyBatis/JSON/MVC/MQ 自动装配、工具类
├── sm-api           Feign 客户端与 DTO（含 Sentinel fallbackFactory）
├── sm-gateway       网关：JWT 校验、路由转发（端口 8080）
├── user-service     用户服务：登录、注册、JWT 签发（8081）
├── item-service     商品服务：商品/搜索/分类，Redis+Caffeine 两级缓存、Sentinel 限流熔断（8082）
├── cart-service     购物车服务：加购、改数量、删除、合并（8083）
├── trade-service    订单服务：下单、取消、删除，Seata 全局事务 + 延迟关单（8085）
├── pay-service      支付服务：余额支付、支付单状态（8086）
├── ai-service       AI 智能助手：多场景对话、流式输出、工具调用、RAG 语义检索（8087，JDK 17 独立构建）
└── StartMall-nginx  前端门户 / 管理后台静态资源与 nginx 配置
```

## AI 智能助手（ai-service）

- **四个独立场景**，各自独立的 ChatClient / 系统提示词 / 模型参数，按关键字自动路由：
  - `shopping` 导购（商品搜索、分类查询、加购承接）
  - `cart` 购物车（查询、加购、移除、改数量）
  - `order` 订单（查询、下单、取消、删除）
  - `support` 通用客服
- **12 个工具**（`@Tool` 中文描述，跨服务数据经 OpenFeign 获取，不直连业务库）：
  - ShoppingTools：`searchItems` / `listCategories` / `getItemById`
  - ProductRagTools：`semanticSearchItems`（向量语义检索）
  - CartTools：`listMyCart` / `addToCart` / `removeFromCart` / `updateCartQuantity`
  - OrderTools：`queryOrderById` / `createOrder` / `cancelOrder` / `deleteOrder`
- **RAG 语义检索**：在售商品拼成文档后用 `text-embedding-v3` 向量化写入 Milvus（1024 维、FLAT 索引、COSINE），共 5000 条；用户提出带偏好/用途/价位的模糊需求（如「拍照好的手机」「适合学生党的耳机」）时优先向量召回，再调 `getItemById` 核对详情
- **索引维护接口**：`POST /ai/rag/rebuild`（全量重建）、`GET /ai/rag/search?query=`（召回自测）、`GET /ai/rag/status`（向量条数）、`DELETE /ai/rag/collection`（建集合参数变更后使用）
- **向量库配置**：Milvus 地址与集合参数在 ai-service 本地 `application-dev.yaml`（`spring.ai.vectorstore.milvus.*`），首次部署后调用一次 rebuild 即可建立索引；索引数据持久化在 Milvus，重启无需重建
- **写操作安全约束**：加购 / 移除 / 改数量 / 下单 / 取消 / 删除订单，均先向用户确认，用户同意后才真正调用工具
- **流式输出**：`POST /ai/chat/stream`（SSE），前端 fetch + ReadableStream 逐字渲染；旧 `POST /ai/chat` 保留兼容
- **会话记忆**：JDBC 持久化到 `sm-ai` 库（`SPRING_AI_CHAT_MEMORY` + `chat_history`），每会话保留最近 10 条；前端打开聊天框自动恢复最近会话
- 网关路由 `/ai/**` → `lb://ai-service`，所有 AI 接口需登录

## 配置中心（Nacos）需要准备的配置

所有配置都在 **DEFAULT_GROUP**，dataId 如下（**内容必须纯 ASCII**，任何中文字符都会导致 SnakeYAML 解析失败并静默丢弃整份配置；中文文案放各服务本地 `application.yaml`）：

**共享配置（多服务复用）：**

| dataId | 内容 |
|---|---|
| `shared-jdbc.yaml` | 数据源（MySQL 连接、连接池），密码用占位符 `${sm.db.pw}` |
| `shared-log.yaml` | 日志级别与格式 |
| `shared-swagger.yaml` | Knife4j 文档（标题用占位符，默认 ASCII） |
| `shared-seata.yaml` | Seata 客户端，**必须显式包含 `seata.tx-service-group: small`** |
| `shared-feign.yaml` | Feign + Sentinel 熔断（`feign.sentinel.enabled=true`） |
| `shared-sentinel.yaml` | Sentinel 控制台接入 |
| `shared-jwt.yaml` | JWT 密钥库路径与口令占位符 `${sm.jwt.password}` |
| `shared-redis.yaml` | Redis 连接（仅 item-service 引用） |
| `shared-mq.yaml` | RabbitMQ 连接，账号/口令/虚拟主机占位符 |

**服务专属配置：** `gateway.yaml`（含登录放行 excludePaths）、`gateway-routes.json`、`item-service.yaml`、`cart-service.yaml`、`user-service.yaml`、`trade-service.yaml`、`pay-service.yaml`。

**本地敏感值**（不入库，已在 .gitignore）：各服务 `application-dev.yaml` 提供
`sm.db.host` / `sm.db.pw` / `sm.redis.password` / `sm.mq.pw` / `sm.jwt.password`，Nacos 配置通过占位符引用。

**命名约定**：服务名 `sm-*` 前缀、包名 `com.small`、配置键 `sm.*`、数据库 `sm-*`、Seata 事务组 `small`、RabbitMQ 需在服务端手动创建 `small` 用户与 `/small` 虚拟主机。

## 启动顺序

1. 基础设施：MySQL → Nacos → Seata → RabbitMQ → Redis → Milvus（standalone，19530 gRPC）
2. 基础业务服务：user-service、item-service、cart-service
3. 跨服务调用：trade-service、pay-service
4. 入口与 AI：sm-gateway、ai-service（JDK 17 单独构建运行）
5. 前端：启动 `StartMall-nginx` 中的 nginx

> 注意：不要同时启动单体版入口与网关（都占 8080）；ai-service 固定 8087（8086 已被 pay-service 占用）。

## 高并发 / 高可用设计

- 库存扣减原子 SQL 守卫（`AND stock >= #{num}`，影响行数为 0 即回滚），杜绝超卖
- Seata AT 全局事务保障下单跨库一致性
- RabbitMQ 异步解耦 + 延迟队列自动关闭超时未支付订单
- Sentinel 限流（商品查询 QPS）+ RT 熔断降级 + Feign fallbackFactory
- 商品查询 Redis + Caffeine 两级缓存，写操作缓存失效
