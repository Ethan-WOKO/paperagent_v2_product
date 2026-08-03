# 真实 Elasticsearch Hybrid RAG E2E 验证报告

> 关联 issue: #35 `eval: 运行真实 Elasticsearch rebuild 与 hybrid RAG e2e 验证`
>
> 日期：2026-07-04

## 1. 结论

本次新增并实跑了一个默认关闭的真实 Elasticsearch hybrid RAG e2e 测试：

```text
RealElasticsearchHybridRagE2eTest
```

验证结果：

| 指标 | 结果 |
| --- | ---: |
| fixture case 总数 | 10 |
| 通过 case | 10 |
| 失败 case | 0 |
| Recall@5 | 1.0 |
| MRR | 0.7 |
| forbidden hit count | 0 |
| metadata preservation rate | 1.0 |

核心结论：

1. 真实 Elasticsearch 8.10.4 可连接并能完成临时 index 创建、写入、刷新、查询和清理。
2. `HybridKnowledgeSearchService` 能通过真实 ES candidate recall，再经 MySQL/H2 仓储 hydrate 和 `KnowledgeDocumentSearchPolicy` 二次过滤。
3. `ACTIVE` 默认注入、`SUPERSEDED` 显式历史查询、`DELETED`/`ARCHIVED` 永不注入均通过验证。
4. 跨用户隔离和 public 文档可见性通过验证。
5. 本次自动化测试没有直接向共享本地 MySQL schema 写 fixture，而是使用 JPA/H2 事实库 + 真实 ES 临时 index，避免污染开发数据。

## 2. 运行环境

真实 Elasticsearch：

```text
endpoint: http://localhost:9200
version: 8.10.4
cluster: docker-cluster
```

测试事实库：

```text
JPA/H2 create-drop schema
```

说明：

1. 本机 `localhost:3306` 可连，但项目开发 compose 默认 MySQL 是 `localhost:3307`。
2. 当前仓库的 `yanban-knowledge` 测试模块没有 MySQL driver / Flyway MySQL 测试依赖。
3. 为避免直接写共享开发库，本次没有把 fixture seed 到长期存在的 MySQL schema。
4. 真实 ES 侧使用临时 index，测试结束后删除。

## 3. 可复现命令

默认 CI / 普通测试不连接真实 ES：

```powershell
mvn -pl yanban-knowledge -am '-Dtest=RealElasticsearchHybridRagE2eTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

真实 ES e2e：

```powershell
mvn -pl yanban-knowledge -am '-Dtest=RealElasticsearchHybridRagE2eTest' '-Dyanban.real-es-e2e=true' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本次实跑结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试输出 JSON：

```text
yanban-knowledge/target/rag-eval/real-es-hybrid-rag-e2e.json
```

可选自定义 ES endpoint：

```powershell
mvn -pl yanban-knowledge -am '-Dtest=RealElasticsearchHybridRagE2eTest' '-Dyanban.real-es-e2e=true' '-Dyanban.real-es-endpoint=http://localhost:9200' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 4. 测试覆盖

测试数据来自：

```text
docs/evaluation/fixtures/rag-spike/
```

自动化步骤：

1. 创建临时 ES index：`yanban-kb-chunks-e2e-{timestamp}`。
2. 创建包含版本治理字段的 mapping：
   - `userId`
   - `projectId`
   - `isPublic`
   - `sourceType`
   - `versionStatus`
   - `lineageId`
   - `versionNo`
   - `canonicalKey`
   - `text`
   - `vector`
3. 将 fixture documents seed 到 JPA 测试事实库。
4. 将 fixture chunks 写入真实 Elasticsearch。
5. 执行 `_refresh`。
6. 通过 `HybridKnowledgeSearchService` 跑 10 个 RAG fixture case。
7. 额外 seed 一个 `ARCHIVED` 文档，并验证显式历史查询也不会注入。
8. 删除临时 ES index。

## 5. Case 结果

| Case | Area | Result | 说明 |
| --- | --- | --- | --- |
| RAG-LC4J-001 | private_active_recall | PASS | User A 可召回 active polished manuscript |
| RAG-LC4J-002 | cross_user_isolation | PASS | User B 不会看到 User A private 文档 |
| RAG-LC4J-003 | public_document_recall | PASS | public RAG note 可被 User B 召回 |
| RAG-LC4J-004 | active_version_priority | PASS | ACTIVE polished 优先于过时版本 |
| RAG-LC4J-005 | deleted_document_filter | PASS | DELETED fixture 未命中 forbidden hit |
| RAG-LC4J-006 | citation_metadata_preservation | PASS | literature card citation 元数据存在 |
| RAG-LC4J-007 | follow_up_query_transform | PASS | 当前 query variant 能召回 active polished |
| RAG-LC4J-008 | low_confidence_behavior | PASS | 无 forbidden hit |
| RAG-LC4J-009 | polished_vs_original | PASS | polished ACTIVE 排在 original SUPERSEDED 前 |
| RAG-LC4J-010 | chinese_query_mixed_language_recall | PASS | 中文 query 可召回 mixed-language academic note |

## 6. 已验证的风险点

### 6.1 用户隔离

ES 查询阶段按：

```text
userId == currentUserId OR isPublic == true
```

测试结果：

```text
forbiddenHitCount = 0
```

### 6.2 版本过滤

默认查询只允许：

```text
ACTIVE
```

显式历史查询允许：

```text
ACTIVE, SUPERSEDED
```

永不注入：

```text
DELETED, ARCHIVED
```

测试结果：

1. `DELETED` fixture 未出现在任何 forbidden hit 中。
2. 额外 seed 的 `ARCHIVED` 文档在 `includeSuperseded=true` 时仍不会返回。
3. `SUPERSEDED` 文档仅在显式历史查询中出现。

### 6.3 临时 index 清理

测试使用临时 index：

```text
yanban-kb-chunks-e2e-{timestamp}
```

测试结束后通过 `DELETE /{index}` 清理。失败时也会在 `finally` 中尝试删除。

## 7. 与 DB baseline 的区别

已有报告：

```text
docs/evaluation/reports/kb-version-governance-rag-eval-20260704.md
```

覆盖：

1. H2 database baseline。
2. `SimpleKnowledgeSearchService`。
3. repository-level 权限和版本过滤。

本次报告覆盖：

1. 真实 Elasticsearch index 创建和写入。
2. 真实 ES 查询 JSON 执行。
3. `HybridKnowledgeSearchService` 从 ES hit 到 JPA hydrate 的完整链路。
4. `DELETED`/`ARCHIVED` 即使进入 ES，也会被 hydrate 策略挡住。

## 8. 剩余风险

1. 本次没有直接向共享本地 MySQL schema 写 fixture。
2. 当前 e2e 使用 deterministic embedding，目的是验证 ES/hybrid/filter 生命周期，不评价真实 embedding 模型质量。
3. citation id 采用当前生产格式 `filename#chunk-0`，不是 fixture 原始 `citationId` 字段；后续如果要保留上传文献卡片的外部 citation id，需要扩展 `KnowledgeSearchResult` 或文档元数据映射。
4. 当前测试没有验证 alias 切换，只验证临时物理 index 创建、写入、查询和删除。

## 9. 建议后续 issue

1. `test: 增加临时 MySQL schema 的 RAG fixture e2e`
   - 在测试启动时创建独立 schema。
   - 运行 Flyway migration。
   - seed fixture。
   - 测试结束后 drop schema。
   - 避免污染 `yanban_agent` 开发库。
2. `implementation: 将原始 citationId 写入 KnowledgeSearchResult`
   - 当前生产 citation id 来自 `filename#chunkIndex`。
   - 文献卡片和论文版本治理需要保留外部 citation id。
3. `ops: 增加 ES alias rebuild 脚本`
   - 创建新物理 index。
   - bulk rebuild。
   - eval gate 通过后切 alias。
