# Verification: 知识库原文件下载

日期：2026-09-14。Issue #221，分支 codex/issue-185-react-optimization。

## Approval / baseline

用户在完整 spec/plan/tasks/分析报告交付后明确回复“批准”，授权进入实施。内置规格清单 16/16，自定义 reviewer 清单保留 12 个未勾选标记，不由实施者代勾；本次明确批准作为继续执行的人工门禁依据，不重复要求批准。

实施前业务代码干净；存在未跟踪 .runtime/ 与本需求 specs/002，保留 .runtime，不提交。Spec Kit feature.json 为本地忽略状态文件，已指向本需求。hooks 为空，无待执行 hook。现有 ignore 规则覆盖构建、依赖和秘密文件。

## Evidence

最终验证通过，命令从仓库根目录执行（前端另注明）。

```powershell
mvn -q -pl yanban-api -am '-Dtest=KnowledgeDocumentServiceTest,KnowledgeRepositoryTest,KnowledgeDocumentPublisherPolicyAdapterTest,KnowledgeDocumentDownloadControllerTest,KnowledgeControllerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

2026-09-14 14:50 完成，40 tests / 0 failures / 0 errors / 0 skipped，5 suites。真实原知识库 Spring Boot/H2 集成 7 项通过，其中普通公开资料跨用户检索断言保持，并增加文件列表不共享的断言。JWT/MVC 下载测试使用真实 SecurityFilterChain、JWT 编解码、角色桥接、文档与搜索服务，mock 仓库和 MinIO；不等于真实存储联调。

随后补充空版本默认与传输失败关流测试，仅重跑直接受影响套件：

```powershell
mvn -q -pl yanban-api -am '-Dtest=KnowledgeDocumentServiceTest,KnowledgeDocumentDownloadControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

14:52 完成，29 tests / 0 failures / 0 errors / 0 skipped。合并最新套件结果为 **42 个不同测试**，不是 40+29：

| Suite | Tests | Failures / Errors / Skipped |
| --- | --- | --- |
| KnowledgeDocumentServiceTest | 14 | 0 / 0 / 0 |
| KnowledgeRepositoryTest | 3 | 0 / 0 / 0 |
| KnowledgeDocumentPublisherPolicyAdapterTest | 3 | 0 / 0 / 0 |
| KnowledgeDocumentDownloadControllerTest | 15 | 0 / 0 / 0 |
| KnowledgeControllerIntegrationTest | 7 | 0 / 0 / 0 |

前端目录：

```powershell
$env:CI='true'
pnpm build
pnpm exec vitest run tests/knowledgeDownload.test.ts tests/knowledgePagePresentation.test.ts
```

最终 14:52 构建通过；Vitest 16 tests / 0 failures（download 7 + presentation 9）。行为测试覆盖下载字节交付、服务端文件名、重复请求抑制、错误 Blob 不保存、失败恢复、临时 URL 清理与文件名筛选；展示契约覆盖共享行隐藏管理按钮。构建仍提示大于 500 kB 的 bundle，不在本功能扩大拆包范围。

```powershell
git diff --check
git diff --cached --check
```

文档链接/任务 ID 唯一性检查和 owned-path 审查纳入交付检查。日志只保留在本地 .runtime，不提交。

## Resolved verification failures

- 最初 Maven 命令未引用带点的 -D 参数，PowerShell 拆分导致 lifecycle 参数错误，未执行测试；引用参数后正常运行。
- 首次 service/repository 测试 15 项中 1 个 Mockito stubbing 错误；原因是在 when/thenThrow 中构造会读取另一个 mock 的 MinIO 异常。提前构造异常并使用 doThrow 修复测试，生产逻辑未为此放宽。
- 首次前端 build 因旧 UI mock fixture 缺少新增 DTO 字段失败。字段改为加法兼容的可选属性；旧响应只列本人，因此缺少 ownedByCurrentUser 时保留本人管理按钮，缺少 downloadAvailable 时不显示下载。新后端明确返回共享 owned=false。未改无关 fixture。
- 完整 Boot 测试启动时 Kafka localhost:9092 未运行，出现非致命连接超时日志；该 7 项套件最终通过。没有启动生产依赖或改变连接配置。

## Scope / residual risks / skipped checks

- 没有修改上传/解析、KnowledgeDocumentSearchPolicy、检索实现、LLM/Agent、数据库 schema、鉴权配置、用户文件或旧规格。共享权限使用访问时当前未删除 ADMIN；无新权限缓存。
- API 应用、MinIO、前端开发服务在本次端口检查中未运行（8080/9000/5173/5174 无监听，仅 3306 可见）。因此未执行真实 MinIO + 点击游客体验 + 浏览器保存文件的端到端人工验收。没有把 MockMvc 的 DEMO JWT 场景当成真实游客登录链路验收。
- 未运行真实模型、全量 RAG eval、Engine 全套或其他产品全量测试，因为这些实现未改动；普通公开搜索与 owner 隔离固定回归已执行。
- 历史 ingestSimple/ingestText 未保存原文件的记录不可下载，需要重新通过现有页面上传；不回填、不以 chunks 伪造文件。
- 列表 downloadAvailable 不探测 MinIO；对象后续丢失在下载时返回 404。当前下载重新鉴权，但已交付的字节不能撤回。后端流式传输，前端沿用 Blob，仍会使用整文件浏览器内存。
- 本需求不新增病毒扫描或发布审核；保持已批准的最小管理员公开下载边界。

## Rollback / restart / delivery

Converge：检查 9 项 FR、4 项 SC、10 个用户验收场景、8 项设计决定及 5 项宪章原则，未发现缺失/矛盾/未请求实现；未追加空 Convergence 阶段。业务实现已收敛，真实存储浏览器联调仍按上面的限制保留为未执行，而非通过。

本功能无 migration 和持久写入变化。回滚本功能提交可恢复旧列表/移除下载，保留所有对象和文档。应用更新需重启 API、重建发布前端；Engine、ES、MinIO 不需配置改动。不要回滚或覆盖同分支既有 #216–219 的工作。

用户批准在现有 185 分支实施。完成后仅提交 owned paths，push 并更新已有 Draft PR #220 关联 #221，不合并；旧 PR 尚未完成的其他验收门禁继续保留。

交付已完成：实现提交 `4266f180` 已推送至 origin/codex/issue-185-react-optimization。[Draft PR #220](https://github.com/Ethan-WOKO/paperagent_v2_product/pull/220) 标题与说明已更新、关联 #221，并保留 #216–219 的既有验收结果和待办。未合并、未启动或重启生产服务。最终仅 .runtime/ 作为原有/本地执行数据保持未跟踪，不纳入提交。

## 用户修订：管理员公开资料允许预览（2026-09-14）

用户明确要求修改并直接授权实施。原规格“共享资料仅下载、不预览”由当前 spec/plan/contracts 取代，#221 合同同步修订；历史验证结果不改写。

页面对本人和管理员公开资料显示 Preview；后端新增 previewVisibleDocument，原 owner-only 内部方法保留，提取同一 buildPreview 复用原字符/分块限制。共享预览与下载共用当前有效管理员公开权限判断，失败在读取 chunks 前拒绝。预览不要求原文件 objectKey，删除、上传、RAG 不变。

精确验证命令（仓库根目录）：

```powershell
mvn -q -pl yanban-api -am '-Dtest=KnowledgeDocumentServiceTest,KnowledgeDocumentDownloadControllerTest,KnowledgeControllerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

16:12 完成：49 tests / 0 failures / 0 errors / 0 skipped（service 14、download/preview MVC 28、原 Boot/H2 knowledge integration 7）。新增 13 个参数化/普通测试场景：成员/管理员本人/DEMO 预览无原文件的解析内容、他人普通公开/私有及管理员私有拒绝、匿名拒绝、角色/账号/公开/处理/版本/删除状态撤销、预览字符截断。越权拒绝验证 chunks/MinIO 零交互。

前端目录：

```powershell
pnpm exec vitest run tests/knowledgeDownload.test.ts tests/knowledgePagePresentation.test.ts
$env:CI='true'
pnpm build
```

16 tests passed，build passed，保留既有 bundle-size 警告。git diff --check 通过。本修订只涉及既有 owned paths；没有新增原格式 PDF/Word 查看器、schema、存储写入或检索变化。

Converge 对照修订后的 FR-007、US2、共享权限和 T016–T019，未发现未完成代码缺口。此前 T010 的“不展示共享预览”由本阶段取代；其他已完成事实保留。

本轮检测到本地 8080/9000/5173 服务监听，但未重启现有服务，也未操作真实账号和资料做浏览器验收；监听不是健康证明。自动化通过不等于运行中后端已加载修改。需要重启/更新 API，前端开发模式可热更新，生产需重建发布。回滚本次预览修订提交可恢复原预览授权，下载能力仍保留。Draft PR #220 保持 Draft、不合并。
