# 知识库原文件下载与管理员公开文档展示

日期：2026-09-14。状态：冻结需求，SDD 实施前审查。

## Objective

在知识库页面保留本人的文档，并展示管理员发布的公开文档；本人可下载自己上传的私人或公开文档，其他成员与游客可下载管理员公开文档。保持现有 LLM/RAG 检索范围。

## Frozen implementation contract

- 下载授权：当前用户为所有者，或文档为公开且其所有者是当前有效的 ADMIN。
- 普通用户的公开文档不新增跨用户列表、预览或下载能力；既有搜索片段与 LLM 检索保持原样。
- 管理员私人文档仅本人可下载，ADMIN 身份不提供下载其他用户私人文档的特权。
- 游客指通过现有游客体验流程取得身份的用户，不新增匿名文件接口。
- 管理员沿用现有上传与公开勾选；不引入发布审核体系或新数据字段。
- 列表加法兼容、去重；他人的管理员公开文档只展示基础元数据和下载操作。原有本人预览、删除与 DEMO_SEED 限制保持。
- 原文件存在于既有对象存储时提供附件下载；历史无原文件记录明确不可下载，不用解析片段拼造原文件。不改上传流程或回填历史文件。
- 文档已删除不可下载。共享列表仅展示 READY、ACTIVE 的管理员公开文档；共享下载使用相同条件。本人原文件下载不依赖解析成功，但已删除或 ARCHIVED 文档不可下载。
- 每次下载重新鉴权，缺失/越权统一不暴露资源；存储故障安全失败，不暴露 objectKey 或存储地址。
- 不修改 RAG、LLM、Agent、ProjectVersion、数据库迁移、部署配置或其他页面。

## Owned paths

- `.specify/feature.json`、`specs/002-kb-document-download/**`
- `yanban-knowledge/src/main/java/com/yanban/knowledge/domain/KbDocumentRepository.java`
- `yanban-knowledge/src/main/java/com/yanban/knowledge/service/KnowledgeDocumentService.java`
- 新增 `yanban-knowledge/src/main/java/com/yanban/knowledge/service/KnowledgeDocumentPublisherPolicy.java`、`KnowledgeDocumentDownload.java`
- `yanban-knowledge/src/main/java/com/yanban/knowledge/web/KnowledgeController.java`、`KbDocumentListItemResponse.java`
- 新增 `yanban-api/src/main/java/com/yanban/api/knowledge/KnowledgeDocumentPublisherPolicyAdapter.java`
- `yanban-api/src/main/java/com/yanban/api/user/SysUserRepository.java`（仅管理员查询）
- 上述知识库行为直接相关的 knowledge service/repository、API knowledge/controller 测试
- `frontend/src/api/knowledge.ts`、`frontend/src/views/KnowledgeBasePage.vue`、必要的 `frontend/src/styles/knowledge-workspace.css` 与直接相关知识库下载测试/辅助函数

## Acceptance and verification

覆盖本人私有/公开下载、成员/游客下载管理员公开文件、普通用户公开文档拒绝下载、管理员私有拒绝、匿名拒绝、已删除和失效角色拒绝、原文件缺失、存储异常、中文文件名、下载字节一致、共享列表去重、本人预览删除回归。固定数据确认普通用户公开文档仍可被其他用户搜索。

运行 focused JUnit/Mockito/JPA/API 测试、知识库 Vitest 与前端 build、`git diff --check`；精确命令、数量、跳过项、风险和回滚记入 quickstart/验收证据。不以文档检查冒充功能验证。

## Branch and delivery

用户明确指定当前 `codex/issue-185-react-optimization` 分支及现有独立 worktree，作为每 Issue 新分支惯例的本次例外。原 #185 已关闭且不拥有知识库路径，因此本需求单独建 Issue，不重写原合同。现有 Draft PR #220 已承载该分支其他工作；实施完成后关联本 Issue 并如实补充该 PR 的最终范围与证据，不重复创建同 head/base PR，不合并。此前 `specs/001-user-capability-expansion` 与未跟踪 `.runtime/` 保留。
