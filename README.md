# 研伴 Agent（Yanban Agent）

一个面向科研学习场景的 AI 研助平台，包含：

- 多轮对话 Agent
- Plan-and-Execute 强规划 Agent（计划生成、DAG 步骤持久化、逐步执行）
- 知识库上传 / 检索 / RAG
- 论文修改工作流
- GLM / DeepSeek 双 Provider
- MCP（GitHub / filesystem）
- Skills 加载与启用控制
- CLI
- Vue 前端页面

## 1. 仓库边界

本仓库是 PaperAgent 的产品主仓库，保留已经调试完成的前端、文献检索、论文润色、
知识库、鉴权和部署能力。V2 Agent 核心以隔离的 Maven reactor 位于：

```text
agent-v2/
```

`agent-v2/` 当前只包含六个核心模块，不包含独立的 `app-api`、`app-web` 或
`e2e-tests`。产品代码以后通过明确的 adapter 依赖 V2 稳定契约；V2 核心不得反向
依赖 `com.yanban`、Controller、产品数据库实体或旧 Agent Service。

本次导入仅建立构建与代码边界，尚未切换任何生产 API 或运行时流量。详细决策见
`docs/当前有效/架构设计/v2-agent-core-integration.md`。

## 2. 阶段 B 当前能力

当前已实现的主要能力：

- JWT 注册 / 登录 / 刷新
- Agent 会话、消息、工具调用持久化
- Plan Agent 计划 / 步骤 / 事件持久化与计划模式 API
- DeepSeek / GLM Provider 路由
- WebSocket 流式聊天
- 计划模式：Planner 生成 JSON DAG，Step Executor 复用 Harness 与工具体系执行
- 默认 RAG 与“本次禁用知识库”开关
- 知识库：
  - simple upload
  - 分片上传 + MinIO 合并
  - Kafka 异步解析
  - DashScope Embedding
  - Elasticsearch 混合检索
  - 文档列表 / 删除 / 搜索调试页
- 论文工作流：
  - docx 上传
  - 任务状态查询
  - SSE 事件流
  - pause / resume / stop
  - 结果下载（当前仍是 skeleton-first 版本）
- MCP：
  - GitHub MCP discovery / proxy
  - filesystem MCP discovery / 路径白名单
- Skills：
  - `skills/builtin/**`
  - `skills/user/**`
  - `SKILL.md` + `skill.yaml`
  - code-review 内置 Skill
- CLI：
  - `yanban login`
  - `yanban chat`
  - `yanban config list/set`
  - `yanban kb list/upload`
  - `yanban paper status`
- Vue 页面：
  - 登录 / 注册
  - 对话
  - 设置
  - 知识库管理
  - 检索调试
  - 论文页

## 3. 环境要求

- JDK 17
- Maven 3.9+
- Docker Desktop
- Node.js 18+
- pnpm 9+

详细说明见：

- `docs/当前有效/部署运维/SETUP.md`

## 4. 本地配置

复制模板：

```bash
cp .env.example .env
```

最少建议配置：

- `JWT_SECRET`
- `DEEPSEEK_API_KEY`（真实模型对话）

阶段 B 常用额外配置：

- `GLM_API_KEY`
- `DASHSCOPE_API_KEY`
- `YANBAN_MCP_GITHUB_ENABLED=true`

> 不要把真实 `.env` 提交到 Git。

## 5. 一键启动中间件

阶段 B 本地联调建议直接启动全量依赖：

```bash
docker compose -f docs/当前有效/部署运维/docker-compose.yml up -d
```

默认组件：

- MySQL：`localhost:3307`
- Redis：`localhost:6379`
- Elasticsearch：`http://localhost:9200`
- Kafka：`localhost:9092`
- MinIO API：`http://localhost:9000`
- MinIO Console：`http://localhost:9001`

说明：

- 本机 `3306` 已被占用，因此项目 MySQL 使用 `3307`。
- Kafka 启动后建议等待约 60 秒再跑异步链路。

## 6. 启动后端

在项目根目录执行：

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

## 8. MCP 与 GLM 配置说明

### 8.1 GLM

在设置页或用户设置 API 中可配置：

- `defaultProvider=glm`
- `glmApiKey`
- `glmModel`

新建会话时会继承默认 Provider / Model 快照。

### 8.2 GitHub MCP

默认预留命令：

```text
npx -y @modelcontextprotocol/server-github
```

需要：

- 本机可用 `node` / `npx`
- 用户设置中已配置 GitHub PAT
- 开启 `YANBAN_MCP_GITHUB_ENABLED=true`

运行时后端会把用户 GitHub PAT 注入为官方变量，并保留旧服务兼容别名：

- `GITHUB_PERSONAL_ACCESS_TOKEN`
- `GITHUB_TOKEN`

### 8.3 filesystem MCP

默认预留命令：

```text
npx -y @modelcontextprotocol/server-filesystem
```

需要：

- 本机可用 `node` / `npx`
- 开启 `YANBAN_MCP_FILESYSTEM_ENABLED=true`
- 用户设置中已配置 `filesystemRoots`

当前后端会先做本地路径白名单校验，再转发到 MCP server。

## 9. Skills 目录说明

Skills 目录结构：

```text
skills/
├── builtin/
│   └── code-review/
│       ├── SKILL.md
│       └── skill.yaml
└── user/
```

当前规则：

- `SKILL.md`：Skill prompt 主内容
- `skill.yaml`：轻量元数据
  - `name`
  - `description`
  - `allowed_tools`
- 运行时扫描：
  - `skills/builtin/**`
  - `skills/user/**`

前端聊天页已支持选择 Skill；后端也支持启用 / 禁用与刷新扫描。

## 10. CLI

`yanban-cli` 当前已支持：

```bash
yanban login
yanban chat
yanban config list
yanban config set max-steps 10
yanban kb list
yanban kb upload ./notes.md
yanban paper status 1
```

CLI 本地配置写入：

```text
~/.yanban-agent/config.properties
```

## 11. 测试与 CI

运行默认测试：

```bash
mvn clean test
```

当前默认测试策略：

- DB：H2（`MODE=MySQL`）
- 外部依赖：优先 Mock / 本地替身
- 不依赖真实 DeepSeek / DashScope / GitHub / MCP 外网服务

已覆盖的核心路径包括：

- Harness 多轮工具调用
- RAG 开关
- Skill 白名单
- JWT 认证
- KB 权限检索

当前选择的是：

- **Mock 外部依赖 / H2 内存库**
- **未使用 Testcontainers 作为默认 CI 方案**

若后续补真实外网测试，应标记：

```java
@Tag("manual")
```

默认 `mvn test` 会排除 `manual`；手动执行可用：

```bash
mvn test -Dgroups=manual
```

> MCP 不属于 Project V2 当前发布门；外部能力必须按明确用例单独审计，Project V2 不使用 filesystem MCP。

## 12. 文档索引

- 统一文档入口：`docs/README.md`
- V2 Agent 核心集成：`docs/当前有效/架构设计/v2-agent-core-integration.md`
- 按变更类型选择测试：`docs/当前有效/开发流程/verification-matrix.md`
- 环境说明：`docs/当前有效/部署运维/SETUP.md`
- 待重新评估的 API/WebSocket 说明：`docs/待重新评估/`
- 历史 v1 评测与上线门禁：`docs/历史归档/已被替代/旧评测体系/`

## 13. 开源卫生

本仓库当前约束：

- 使用 `LICENSE`：Apache-2.0
- 不提交真实 `.env`
- 不提交真实 API Key / PAT
- `target/`、`node_modules/`、运行时目录均应被忽略

说明：

- 当前工作区可见本地 `target/` 与 `frontend/node_modules/`，但它们位于忽略规则范围内。
- 由于当前 WSL 视图缺少 `.git` 元数据，本轮无法在此环境下完成最终 git-index 级别的提交内容核验；需后续在可见 `.git` 的环境再补一轮开源卫生复查。
