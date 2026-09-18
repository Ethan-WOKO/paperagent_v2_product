# 工作区会话附件与知识库分离

Issue: #225。范围仅为工作区聊天产品适配，保留 Project/ReAct 和原知识库流程。

## 用户行为

- “添加附件”将文件保存在当前会话，普通提问直接使用文档正文或图片，不创建知识库文档、分片、向量或入库任务。
- 附件随会话保存，刷新页面、再次进入会话和后续追问均可使用。删除按钮表示从后续请求中移除，已经开始的请求/计划保留其冻结的附件选择。
- “加入知识库”是显式动作，复用现有私有知识库入库流程。重复点击同一附件返回同一知识库文档，索引异步完成，不影响会话提问。
- 独立“知识库”入口仍可上传长文档。已有知识库文件和聊天中的 documentId 引用保持有效，不迁移或删除旧数据。
- 需要正式异步论文润色任务的 .tex/.bib 文件，先明确加入知识库，再使用原论文任务流程；普通附件问答直接使用会话内容。

## 首版边界

支持 PDF、DOC、DOCX、UTF-8 TXT/MD/TEX/BIB/CSV/JSON，以及 PNG/JPEG。单文件最多 10 MiB；正文每份最多 24000 字符、活动附件正文总计最多 48000 字符。每会话最多同时使用 8 份附件，累计最多上传 32 份；图片最多 4 张、合计 16 MiB，每张最多 2500 万像素。

超限、空文档、无可读取正文、格式错误或存储失败均返回可见状态，不能静默截断后继续回答。上传/解析过程中允许继续输入，但发送等待处理完成；失败附件需要移除或重新上传。首版不实现长文档分页工具、自动向量化、自动模型切换或图片 OCR 替代视觉输入。

## 配置与部署

新增 V105（生产 MySQL 和测试 H2）创建两张独立表：`agent_session_attachments`、`agent_attachment_snapshots`。附件改动已按用户要求同步回 `codex/issue-185-react-optimization`。后续在 `C:/java_file/private_helper_Agent/paperagent_v2_product_issue_185_react_optimization` 构建和启动前后端，无需切换到 225 工作区。已启动的后端需要重新启动才能加载新接口及 V105。

- `yanban.attachments.enabled` 默认 `true`，只控制新附件上传。设为 `false` 时，已保存的附件仍可读取和用于聊天。
- `yanban.attachments.vision-models` 默认空，按逗号分隔填写精确的 `<providerKey>:<modelName>`。必须使用设置页实际配置的 providerKey/modelName，并确认该服务支持 OpenAI-compatible `image_url` 内容。可通过 Spring Boot 属性或 `YANBAN_ATTACHMENTS_VISION_MODELS` 环境变量配置。
- 空列表或未匹配的模型在保存用户消息之前拒绝图片请求，提示切换已启用视觉能力的模型。系统不根据名称猜测能力，也不默默丢弃图片。流式和非流式、备用路由均检查并保留图片内容。
- 沿用现有 MinIO bucket、账户文件权限和上传并发限制；附件使用独立 `session-attachments/` 对象前缀。原件及解析正文为私有持久资料。移除附件保留其原件用于已冻结请求，不等于物理删除；对象存储保留/回收策略需要按部署的数据保留政策管理。

## 实现边界

上传接口为 `/api/v1/agent/sessions/{sessionId}/attachments`。服务端验证登录用户、会话归属及 WORKSPACE 范围，禁止利用此入口绕过 Project 权限。前端仅取得元数据，不暴露存储路径或图片 data URL。

原聊天请求 DTO 保持兼容，由服务器保存的活动附件决定本会话的资料范围。普通聊天记录只保存用户原话及原有显式知识库引用；正文和图片在最终模型适配处加入用户消息，不作为用户发言写入长期记忆来源。文档按文件名标注，图片发送真实内联图片内容。

在每个运行标识第一次模型调用时，服务端持久冻结附件 ID 列表（包含空列表）；同一 Plan 的步骤共享选择。后续上传/移除不会改变已经开始调用模型的运行。每次读取均重新核对用户、会话和附件归属。现有 ChatMessage 四参数构造及纯文本序列化兼容，核心模型消息增加可选图片列表。

## 验证

Focused Maven 命令（根目录）：

```powershell
mvn -pl yanban-api -am '-Dtest=SessionAttachmentServiceTest,AttachmentParserTest,SessionAttachmentMigrationTest,LangChain4jChatModelAdapterTest,AgentModelRoutingServiceTest,PlanAgentServiceTest,GlmModelProviderTest,DeepSeekModelProviderTest,MultimodalProviderTest,AgentServiceRuntimeAssemblyTest,AgentServicePaperRoutingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：128 项通过（core 16、API 112），0 失败/错误/跳过。包括真实 Spring Security/JWT/MockMvc/H2 上传与隔离，上传不产生知识库记录，显式入库幂等，解析失败、文件容量、图片格式、文档实际进入模型请求，图片在三个 Provider 的 HTTP/SSE 请求体中保留，以及现有计划/纯文本/论文路径。

受影响的 PlanAgentServiceTest 在基线 dbb6a834 上亦存在同样的 13 项失败（69 项中 7 failures、6 errors）：模拟和验证仍针对旧 planner 重载。仅更新测试方法签名到现行接口后，69 项全部通过；未改变 planner 行为。

前端命令（frontend 目录）：

```powershell
pnpm exec vitest run tests/sessionAttachments.test.ts tests/paperPolishInput.test.ts
$env:CI='true'
pnpm build
```

11 项测试通过，生产构建通过。检查 `git diff --check`。构建仍有现有大 bundle 提示。

未运行真实付费视觉模型、真实 MinIO/MySQL 和浏览器登录验收；测试使用存储 mock、本地 HTTP/SSE 模型服务和 H2。没有执行无关产品全量测试或改动 V2 核心。
