# 研伴 Agent 本地开发环境说明

本文档描述当前仓库本地联调所需环境；实际版本和模块以根 `pom.xml`、前端依赖文件及当前 Compose 配置为准。

## 1. 基础工具最低版本

- JDK 17
- Maven 3.9+
- Docker Desktop
- Node.js 18+
- pnpm 9+

说明：

- Node.js 主要用于：
  - 前端开发
  - MCP Server 通过 `npx` 启动
- 建议使用 `node -v`、`pnpm -v`、`mvn -v` 先做本地检查。

## 2. 需要准备的密钥

真实密钥只允许通过环境变量、本地 `.env` 或用户设置接口配置，不得提交到 Git。

- `JWT_SECRET`：JWT 签名密钥，至少 32 字符
- `DEEPSEEK_API_KEY`：DeepSeek 对话
- `GLM_API_KEY`：智谱 GLM Provider
- `DASHSCOPE_API_KEY`：DashScope Embedding 与知识库 `qwen3-rerank`
- `GITHUB_TOKEN`：GitHub MCP（也可通过设置页写入）

知识库生产检索默认使用训练集选出的加权 RRF（BM25 `0.5`、向量 `1.0`、rank constant
`10`）生成最多 50 个候选，再将排名前 20 的候选交给 `qwen3-rerank`。未配置 `DASHSCOPE_API_KEY`、接口超时、
限流或返回异常时会自动降级为加权 RRF，不会中断知识库搜索。可使用
`YANBAN_KNOWLEDGE_RERANK_ENABLED=false` 临时关闭模型重排。

参考模板：

- 项目根目录 `.env.example`

与工具路由相关的可选配置：

- `YANBAN_AGENT_LLM_NATIVE_TOOL_ROUTING_ENABLED=true`
  - 开启后，系统不再按关键词预判本轮要开放哪些 Agent 工具，而是默认暴露常规 Agent 工具给 LLM，由模型自行决定是否调用。
  - 仍保留硬约束：`ragDisabled=true` 时隐藏 `search_knowledge`；skill allowlist 仍然生效；MCP 工具不会默认暴露给普通对话。

## 3. 中间件清单

| 组件 | 默认地址 | 用途 |
|------|----------|------|
| MySQL 8 | `localhost:3307` | 主业务库 |
| Redis 7 | `localhost:6379` | 预留缓存 / 会话扩展 |
| Elasticsearch 8.10.4 | `http://localhost:9200` | 知识库混合检索 |
| Kafka | `localhost:9092` | 文件异步处理 |
| MinIO API | `http://localhost:9000` | 知识库 / 论文对象存储 |
| MinIO Console | `http://localhost:9001` | MinIO 管理页面 |

说明：

- 本机 `3306` 已被占用，因此项目 MySQL 使用宿主机端口 `3307`。
- Kafka 启动后建议等待约 60 秒，再跑消费者或异步上传链路。

## 4. 启动中间件

### 4.1 最小阶段 A 启动

```bash
docker compose -f docs/当前有效/部署运维/docker-compose.yml up -d mysql redis
```

### 4.2 阶段 B 全量启动

```bash
docker compose -f docs/当前有效/部署运维/docker-compose.yml up -d
```

### 4.3 建议验证

```bash
docker compose -f docs/当前有效/部署运维/docker-compose.yml ps
curl http://localhost:9200/_cluster/health
```

还应确认：

- Kafka topic：`file-processing`
- MinIO bucket：`yanban-agent`
- Elasticsearch 索引模板：`yanban-kb-chunks-v2`（首次访问时自动创建，并从兼容的 `v1` 派生索引迁移）

说明：

- `docs/当前有效/部署运维/docker-compose.yml` 现在会通过 `minio-init` 服务自动创建 `yanban-agent` bucket，并设置为 `public`。
- 当前后端也会在首次知识库上传时尝试自动创建 `yanban-agent` bucket，作为额外兜底。
- 如果 MinIO 使用了受限凭证而不是 root 凭证，需要确保该账号有创建 bucket 的权限。

## 5. 启动顺序

1. 启动 Docker 中间件。
2. 准备 `.env` 或系统环境变量。
3. 启动后端。
4. 启动前端。
5. 如需 MCP，确认本机 `node` / `npx` 可用。

## 6. 启动后端

```bash
mvn -pl yanban-api spring-boot:run -Dspring-boot.run.profiles=dev
```

健康检查：

```bash
curl http://localhost:8080/actuator/health
```

## 7. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

默认地址：

```text
http://localhost:5173
```

## 8. MCP 额外准备

若要启用 GitHub MCP 与 filesystem MCP，需本机具备 Node.js 18+ 且可使用 `npx`。

### 8.1 GitHub MCP

默认命令（Windows 推荐）：

```text
cmd /c npx -y @modelcontextprotocol/server-github
```

若在 Linux / macOS，可继续使用：

```text
npx -y @modelcontextprotocol/server-github
```

相关环境变量：

- `YANBAN_MCP_GITHUB_ENABLED`
- `YANBAN_MCP_GITHUB_COMMAND`
- `YANBAN_MCP_GITHUB_ALLOWED_COMMANDS`

说明：

- GitHub PAT 可通过设置页写入后端加密保存。
- 运行时后端会注入为 `GITHUB_TOKEN`。
- 后端会检查可执行命令是否落在 allowlist 中，防止任意命令注入。
- 在 Windows + IDEA + Java `ProcessBuilder` 场景下，直接使用 `npx` 可能无法稳定拉起子进程，因此默认推荐使用 `cmd /c npx ...`。

### 8.2 filesystem MCP

默认命令（Windows 推荐）：

```text
cmd /c npx -y @modelcontextprotocol/server-filesystem
```

若在 Linux / macOS，可继续使用：

```text
npx -y @modelcontextprotocol/server-filesystem
```

相关环境变量：

- `YANBAN_MCP_FILESYSTEM_ENABLED`
- `YANBAN_MCP_FILESYSTEM_COMMAND`
- `YANBAN_MCP_FILESYSTEM_ALLOWED_COMMANDS`

说明：

- 用户设置中的 `filesystemRoots` 决定允许访问的根目录。
- 后端会先做本地路径白名单判断，再调用远端 MCP 工具。

## 9. OCR 额外准备

当前已提供最小 OCR 抽象：

- `yanban.knowledge.ocr.enabled`
- `yanban.knowledge.ocr.api-url`
- `yanban.knowledge.ocr.api-key`
- `yanban.knowledge.ocr.timeout`

当前行为：

- `mimeType` 为 `image/*` 时，知识库异步处理链路会走 OCR 分支。
- 若未配置 OCR Provider，图片文档会进入 `FAILED`，错误提示为 `OCR 未配置`。

## 10. 测试

默认执行：

```bash
mvn clean test
```

默认测试特点：

- 使用 H2 内存数据库（`MODE=MySQL`）
- 外部依赖尽量 Mock 化
- 不要求真实 DeepSeek / DashScope / GitHub / MCP 外网可达

若后续补真实外网测试，统一使用：

```java
@Tag("manual")
```

手动执行：

```bash
mvn test -Dgroups=manual
```

## 11. 开源前检查

至少检查：

- 无真实 `.env`
- 无真实 API Key / PAT
- `target/`、`node_modules/`、运行时目录未纳入版本控制
- `LICENSE` 已存在

说明：

- 当前工作区中可见本地 `target/` 与 `frontend/node_modules/`，但它们属于忽略目录。
- 若要做最终开源卫生核验，建议在可见 `.git` 元数据的环境执行 `git status` / `git grep` 再复核一遍。
