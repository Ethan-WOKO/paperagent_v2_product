# 研伴 Agent 部署说明（后续拓展草案）

> 文档分类：**待重新评估**。当前服务器更新以 `scripts/server` 和生产 Compose 为准，本文尚未同步最近的仓库切换和 V2 Docker 构建修复。

> 本文档用于记录服务器部署与生产化整理方向。当前第二期主线优先论文润色质量专项，完整服务器部署不阻塞第二期门禁；本文仅作为后续拓展草案持续维护。

## 1. 部署目标

- 单机服务器优先。
- Docker Compose 管理 MySQL、Redis、Elasticsearch、Kafka、MinIO。
- 后端以 Spring Boot jar 或容器运行。
- 前端通过 Vite build 后由 Nginx 提供静态资源。
- Nginx 负责 HTTPS、反向代理、WebSocket 转发。

## 2. 推荐组件

| 组件 | 用途 |
|---|---|
| Nginx | HTTPS、静态资源、反向代理 |
| MySQL 8 | 用户、会话、知识库、论文任务 |
| Redis 7 | 缓存 / 后续 token 状态 |
| Elasticsearch 8 | 知识库检索 |
| Kafka | 文件处理异步队列 |
| MinIO | 原始文件与论文结果存储 |
| Java 17/21 | 后端运行 |
| Node 18+ / pnpm | 前端构建 |

## 3. 环境变量

生产环境必须通过环境变量或服务器私有配置注入：

- `JWT_SECRET`
- `DEEPSEEK_API_KEY`
- `GLM_API_KEY`
- `DASHSCOPE_API_KEY`
- `MINIO_ROOT_USER`
- `MINIO_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `YANBAN_SETTINGS_CRYPTO_KEY`

禁止在 Git 中提交真实密钥。

## 4. 数据目录规划

建议将数据目录放到独立磁盘或可备份路径：

```text
/opt/yanban-agent/
  data/
    mysql/
    redis/
    elasticsearch/
    kafka/
    minio/
  logs/
  config/
  app/
```

## 5. Nginx 反向代理要点

需要支持：

- `/api/` 代理到后端 `8080`
- `/api/v1/ws/chat` WebSocket upgrade
- 前端 history fallback 到 `index.html`
- HTTPS 证书配置

## 6. 备份恢复

最低备份内容：

1. MySQL dump。
2. MinIO bucket 数据。
3. Elasticsearch index snapshot 或可由 MySQL + MinIO 重建的索引任务。
4. `.env` / 服务器私有配置。

## 7. 第二期待补

- 生产 docker-compose 文件。
- Nginx 示例配置。
- 后端 jar / Dockerfile 二选一部署方案。
- 一键初始化脚本。
- 备份恢复命令示例。
