# 知识库版本治理字段与 RAG 过滤策略

> 关联 issue: #22 `design: 补齐知识库版本治理字段和 RAG 过滤策略`
>
> 本文定义知识库文档版本治理、论文原稿/润色稿 lineage、文献卡片复用、未来 Project 预留字段，以及 RAG 检索侧和生成侧的过滤/排序规则。当前 PR 只做设计，不修改 schema。

## 1. 背景

LangChain4j RAG spike 和 H2 baseline eval 暴露了一个关键问题：当前 `KbDocument` 只有基础字段：

```text
userId
filename
status
isPublic
sourceType
objectKey
mimeType
fileSize
errorMessage
createdAt
updatedAt
```

这些字段不足以支撑后续目标：

1. 论文原稿和润色稿同时入库，但检索时应优先使用最新、有效、润色后的版本。
2. 过时 draft 不应影响后续判断。
3. 用户删除或废弃的资料不能再进入 RAG。
4. 文献卡片需要沉淀并复用，避免重复检索。
5. 未来 Project/Workspace 需要可空预留，但当前不实现完整 Project。
6. LangChain4j adapter 或 side index 必须保留这些业务过滤条件。

## 2. 设计原则

1. MySQL 是知识资产 metadata 的事实源。
2. Elasticsearch 是可重建检索索引，不作为权限和版本治理事实源。
3. 过滤必须发生在检索前或检索时，不能只靠生成后 prompt 约束。
4. `DELETED`、`ARCHIVED` 等不可用版本不得进入模型上下文。
5. `ACTIVE` 优先于 `SUPERSEDED`，但 `SUPERSEDED` 可以在用户明确询问历史版本时被检索。
6. Project 当前只预留 nullable 字段，不实现项目权限模型。
7. 不一次性重写现有知识库表和上传流程，采用兼容 migration。

## 3. 建议字段

优先扩展 `kb_documents`，首批不拆新表：

```text
project_id BIGINT NULL
lineage_id VARCHAR(64) NULL
version_no INT NOT NULL DEFAULT 1
version_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
source_task_type VARCHAR(64) NULL
source_task_id BIGINT NULL
source_artifact_id BIGINT NULL
source_document_id BIGINT NULL
canonical_key VARCHAR(128) NULL
effective_at TIMESTAMP NULL
superseded_at TIMESTAMP NULL
deleted_at TIMESTAMP NULL
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `project_id` | 未来 Project/Workspace 预留，当前可空 |
| `lineage_id` | 同一论文/文献/资料的版本链 id |
| `version_no` | lineage 内版本号 |
| `version_status` | `ACTIVE/SUPERSEDED/DELETED/ARCHIVED` |
| `source_task_type` | 产出来源，例如 `PAPER_POLISH`、`LITERATURE_SEARCH` |
| `source_task_id` | 来源任务 id |
| `source_artifact_id` | 来源 artifact id |
| `source_document_id` | 派生自哪个旧文档 |
| `canonical_key` | 去重 key，例如规范化标题、DOI、hash 或 lineage key |
| `effective_at` | 版本生效时间 |
| `superseded_at` | 被替代时间 |
| `deleted_at` | 软删除时间 |

## 4. 枚举建议

### 4.1 version_status

```text
ACTIVE
SUPERSEDED
DELETED
ARCHIVED
```

含义：

1. `ACTIVE`：默认可检索、可注入。
2. `SUPERSEDED`：被新版本替代，默认降权；用户明确问历史版本时可检索。
3. `DELETED`：用户删除或系统删除，禁止检索和注入。
4. `ARCHIVED`：归档保留，默认不注入，管理/调试可查。

### 4.2 source_type

当前已有 `sourceType`，建议规范化取值：

```text
USER_UPLOAD
PAPER_ORIGINAL
PAPER_POLISHED
PAPER_SECTION
LITERATURE_CARD
LITERATURE_SEARCH_REPORT
MEMORY_EXPORT
DEMO
PUBLIC_NOTE
```

排序优先级建议：

```text
PAPER_POLISHED > PAPER_ORIGINAL
LITERATURE_CARD > LITERATURE_SEARCH_REPORT
USER_UPLOAD > DEMO
```

## 5. 索引建议

MySQL：

```text
idx_kb_documents_user_status_updated(user_id, version_status, updated_at)
idx_kb_documents_lineage_version(lineage_id, version_no)
idx_kb_documents_project_status(project_id, version_status)
idx_kb_documents_source_task(source_task_type, source_task_id)
idx_kb_documents_canonical_key(user_id, canonical_key)
```

注意：

1. `project_id` 当前 nullable，索引可以先建，业务上不强依赖。
2. `lineage_id` 为空时表示独立文档。
3. 旧数据 migration 默认 `lineage_id = 'kbdoc-' + id` 可以由后续补偿脚本填充，也可以先允许为空。

## 6. 旧数据默认值

Migration 后旧数据建议：

```text
project_id = NULL
lineage_id = NULL
version_no = 1
version_status = ACTIVE
source_task_type = NULL
source_task_id = NULL
source_artifact_id = NULL
source_document_id = NULL
canonical_key = NULL
effective_at = created_at
superseded_at = NULL
deleted_at = NULL
```

原因：

1. 不破坏现有知识库上传和检索。
2. 没有 lineage 的旧文档仍按独立 ACTIVE 文档处理。
3. 后续可以通过离线脚本补 lineage/canonicalKey。

## 7. 论文入库策略

论文任务完成后应至少产生两类知识文档：

1. 原稿：`sourceType = PAPER_ORIGINAL`
2. 润色稿：`sourceType = PAPER_POLISHED`

建议：

```text
lineage_id = paper-{paperTaskId} 或 stable content lineage
PAPER_ORIGINAL.version_no = 1
PAPER_ORIGINAL.version_status = SUPERSEDED
PAPER_POLISHED.version_no = 2
PAPER_POLISHED.version_status = ACTIVE
PAPER_POLISHED.source_document_id = originalDocumentId
```

如果用户重新润色同一论文：

1. 新润色稿成为 `ACTIVE`。
2. 旧润色稿变为 `SUPERSEDED`。
3. 原稿继续保留，但默认不优先注入。

## 8. 文献卡片策略

文献卡片首批仍采用用户内去重：

```text
sourceType = LITERATURE_CARD
canonical_key = doi / arxivId / normalizedTitleYearAuthors
version_status = ACTIVE
```

如果同一用户重复检索到同一文献：

1. 优先复用已有 ACTIVE card。
2. 如果 metadata 更完整，可以创建新 version 并将旧版本 `SUPERSEDED`。
3. 用户偏好不直接写死在全局 card 上，后续采用 `literature_user_preferences` 或独立偏好表。

## 9. 检索过滤策略

默认 RAG 检索必须加过滤：

```text
visible_to_user =
  document.user_id = currentUserId
  OR document.is_public = true

version_allowed =
  version_status = ACTIVE

project_allowed =
  project_id IS NULL
  OR project_id = currentProjectId
```

默认查询：

```text
WHERE status = 'READY'
  AND version_status = 'ACTIVE'
  AND (user_id = :userId OR is_public = true)
  AND (:projectId IS NULL OR project_id IS NULL OR project_id = :projectId)
```

历史版本查询需要显式开关：

```text
includeSuperseded = true
```

即使打开历史版本，也禁止：

```text
version_status IN ('DELETED', 'ARCHIVED')
```

除非进入管理员/调试专用接口，且不得注入模型上下文。

## 10. 排序策略

检索后排序建议：

```text
finalScore =
  vectorScore
  + lexicalBonus
  + sourceTypeBoost
  + versionBoost
  + recencyBoost
  - supersededPenalty
```

建议初始权重：

```text
versionBoost:
  ACTIVE = +0.20
  SUPERSEDED = -0.50

sourceTypeBoost:
  PAPER_POLISHED = +0.20
  PAPER_ORIGINAL = +0.05
  LITERATURE_CARD = +0.15
  PUBLIC_NOTE = +0.05

recencyBoost:
  最近 30 天 = +0.05
```

注意：

1. 排序权重必须可测试，不要藏在 prompt 中。
2. 生成侧仍需看到 sourceType/versionStatus，用于解释为什么选择某版本。

## 11. Elasticsearch 同步策略

ES document 应包含：

```text
documentId
userId
projectId
isPublic
sourceType
versionStatus
lineageId
versionNo
canonicalKey
chunkIndex
text
vector
updatedAt
```

ES 查询必须携带：

```text
filter:
  status/versionStatus/userId/isPublic/projectId
```

推荐策略：

1. MySQL 仍是事实源。
2. ES 中保存过滤字段用于 recall 阶段过滤。
3. ES 返回命中后回 MySQL hydrate，二次确认权限和版本状态。
4. ES index 可重建，字段变更走 versioned index + alias。

## 12. LangChain4j 适配要求

adapter-only：

1. `KnowledgeSearchServiceContentRetriever` 必须从当前服务接收已经过滤后的结果。
2. `TextSegment.metadata()` 必须保留 `documentId/sourceType/versionStatus/lineageId/citationId`。
3. `ContentInjector` 或生成 prompt 不能丢失引用 metadata。

side index：

1. LangChain4j EmbeddingStore 不得绕过 user/version/project filter。
2. 如果 LangChain4j filter 能力不足，则必须继续使用自定义 `ContentRetriever`。
3. 不允许把权限过滤交给模型回答阶段。

## 13. API 和 UI 影响

普通用户 UI：

1. 不需要暴露版本字段细节。
2. 可在调试页或任务详情中显示“使用了哪个版本的文档”。
3. 用户删除知识文档时设置 `DELETED`，不物理删除。

调试 UI：

1. 可显示 lineage。
2. 可显示 ACTIVE/SUPERSEDED。
3. 可显示某次 RAG 检索跳过了哪些文档以及原因。

## 14. 测试要求

后续 implementation issue 至少覆盖：

1. 旧数据 migration 默认 ACTIVE。
2. `DELETED` 文档不被 `KnowledgeSearchService` 返回。
3. `SUPERSEDED` 默认不返回或降权。
4. `includeSuperseded=true` 时历史版本可查但低于 ACTIVE。
5. `PAPER_POLISHED` 排在同 lineage 的 `PAPER_ORIGINAL` 前。
6. user A 不能看到 user B 私有文档。
7. public 文档对 user A/user B 可见。
8. ES hit 回 MySQL hydrate 后再次过滤 `DELETED`。
9. LangChain4j adapter 保留 version metadata。

## 15. 后续 issue 建议

1. `implementation: 扩展 kb_documents 版本治理字段 migration`
2. `implementation: 更新 KbDocument 实体和旧数据默认值`
3. `implementation: 在 KnowledgeSearchService 中加入版本过滤和 sourceType 排序`
4. `implementation: 更新 Elasticsearch index mapping 与重建脚本`
5. `eval: 将 RAG fixture 的 ACTIVE/SUPERSEDED/DELETED case 跑到真实 baseline`

## 16. 非目标

1. 不实现完整 Project/Workspace。
2. 不做全局文献 canonical library。
3. 不在本阶段替换 LangChain4j side index。
4. 不删除旧知识库数据。
5. 不把用户删除变成物理删除。
