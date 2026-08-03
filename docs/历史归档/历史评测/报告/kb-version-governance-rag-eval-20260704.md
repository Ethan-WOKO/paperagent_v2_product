# 知识库版本治理 RAG Baseline Eval - 2026-07-04

> 关联 issue: #29 `eval: 将 RAG fixture 的 ACTIVE/SUPERSEDED/DELETED case 扩展到真实 baseline`

## 1. 结论

当前结论：`PASS_WITH_ES_RUNTIME_GAP`

已证明：

1. RAG fixture 可以 seed 到真实 `KbDocument/KbChunk` JPA/H2 baseline。
2. `ACTIVE` 文档默认可检索。
3. `SUPERSEDED` 文档默认不返回，显式 `includeSuperseded=true` 时可查。
4. `DELETED` 文档即使存在于数据库，也不会返回。
5. `ARCHIVED` 文档在默认查询和历史版本查询中都不会注入。
6. 同一 lineage 下，`PAPER_POLISHED` ACTIVE 文档优先于 `PAPER_ORIGINAL` SUPERSEDED 文档。
7. user A/user B 私有文档隔离仍成立，public 文档仍可见。
8. LangChain4j adapter metadata 可保留 `sourceType/versionStatus/lineageId/versionNo/projectId/canonicalKey`。

未证明：

1. 没有连接真实 Elasticsearch 运行 end-to-end hybrid recall。
2. 没有真实 LLM 生成侧 faithfulness/citation coverage 评估。
3. 没有真实线上数据规模下的延迟和召回质量指标。

## 2. 验证命令

```text
mvn -pl yanban-knowledge -am '-Dtest=HybridKnowledgeSearchServiceTest,BaselineRagDatabaseEvalTest,KnowledgeRepositoryTest,LangChain4jAdapterRagRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl yanban-knowledge -am '-Dtest=BaselineRagDatabaseEvalTest,BaselineRagRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl yanban-knowledge -am test
```

最新结果：

```text
yanban-knowledge module: 25 tests, failures 0, errors 0, skipped 0
```

## 3. 数据集

Fixture 根目录：

```text
docs/evaluation/fixtures/rag-spike/
```

固定 fixture：

| Fixture id | 用户 | 可见性 | sourceType | versionStatus | 用途 |
| --- | --- | --- | --- | --- | --- |
| 1001 | 101 | PRIVATE | PAPER_ORIGINAL | SUPERSEDED | 原稿历史版本 |
| 1002 | 101 | PRIVATE | PAPER_POLISHED | ACTIVE | 当前润色稿 |
| 1003 | 101 | PRIVATE | LITERATURE_CARD | ACTIVE | 文献卡片 |
| 1004 | 101 | PRIVATE | PAPER_DRAFT | SUPERSEDED | 过时旧稿 |
| 1005 | 101 | PRIVATE | LAB_NOTE | DELETED | 已删除资料 |
| 2001 | 202 | PRIVATE | LAB_NOTE | ACTIVE | 其他用户私有资料 |
| 3001 | public | PUBLIC | PUBLIC_NOTE | ACTIVE | 公共资料 |

补充自动化用例：

```text
archived-note.md
versionStatus = ARCHIVED
query = archived-only-calibration-token
```

该用例不进入 fixture JSON，原因是它只验证 `ARCHIVED` 永不注入的底层策略，不参与 10 条 RAG fixture case 的召回评分。

## 4. Case 结果

| Case | 目标 | 当前结果 |
| --- | --- | --- |
| RAG-LC4J-001 | user A 检索 ACTIVE polished manuscript | PASS |
| RAG-LC4J-002 | user B 不能看到 user A 私有文档 | PASS |
| RAG-LC4J-003 | public 文档对 user B 可见 | PASS |
| RAG-LC4J-004 | ACTIVE polished 优先于 obsolete draft | PASS |
| RAG-LC4J-005 | DELETED 文档不返回 | PASS |
| RAG-LC4J-006 | 文献卡片 citation metadata 保留 | PASS |
| RAG-LC4J-007 | follow-up query fixture 路径 | 仍依赖当前 query variants，非生成侧上下文改写 |
| RAG-LC4J-008 | 无证据时不应命中私有/删除资料 | PASS |
| RAG-LC4J-009 | polished vs original 优先级 | PASS |
| RAG-LC4J-010 | 中英混合查询召回 polished 文档 | PASS |

说明：

1. `BaselineRagDatabaseEvalTest` 运行的是 H2 + `SimpleKnowledgeSearchService`。
2. `HybridKnowledgeSearchServiceTest` 验证 ES hit hydrate 后会过滤 `DELETED`，并在全不可用时回退数据库 baseline。
3. `ElasticsearchKnowledgeSearchIndexClientTest` 验证 ES 查询 JSON 包含 `versionStatus` 和可选 `projectId` filter。

## 5. 禁止命中验证

当前自动化验证的禁止命中：

```text
DELETED: forbidden hit count = 0
ARCHIVED: default search = empty; includeSuperseded search = empty
cross-user private: user B 不命中 user A 私有文档
```

关键规则：

```text
默认允许: ACTIVE
显式历史允许: ACTIVE, SUPERSEDED
永不注入: DELETED, ARCHIVED
```

## 6. 排序验证

验证目标：

```text
PAPER_POLISHED ACTIVE > PAPER_ORIGINAL SUPERSEDED
PAPER_POLISHED ACTIVE > PAPER_DRAFT SUPERSEDED
```

已验证：

1. 默认查询只返回 ACTIVE polished，不返回 SUPERSEDED 原稿/旧稿。
2. `includeSuperseded=true` 时，原稿/旧稿可查，但 polished 排在 original 前。
3. `KnowledgeSearchResult` 保留 `sourceType/versionStatus/lineageId/versionNo/projectId/canonicalKey`，后续生成侧可以解释版本选择。

## 7. ES 覆盖情况

已覆盖：

1. 向量化写入 ES 的 `IndexedChunkDocument` 包含版本治理 metadata。
2. ES query JSON 默认带 `versionStatus=['ACTIVE']`。
3. 显式历史查询带 `versionStatus=['ACTIVE','SUPERSEDED']`。
4. 指定 Project 时，ES query 带 `projectId` filter。
5. ES hit 回 MySQL hydrate 后仍二次过滤。

未覆盖：

1. 未启动真实 Elasticsearch index。
2. 未执行真实 index rebuild。
3. 未测真实 vector recall 指标。

后续如要补齐，应基于 `docs/operations/kb-elasticsearch-index.md` 新建手动或集成测试 issue。

## 8. 最终判断

知识库版本治理的 RAG baseline 已经从设计进入可验证实现：

```text
DB baseline: pass
hydration safety: pass
metadata preservation: pass
ES query JSON: pass
real ES rebuild/e2e: not covered
generation faithfulness: not covered
```

因此，#29 可以关闭，但后续仍需要独立任务覆盖真实 ES 环境和生成侧评测。
