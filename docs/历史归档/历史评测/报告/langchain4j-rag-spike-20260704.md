# LangChain4j RAG Spike Report - 2026-07-04

> 关联 issue: #16 `decision: 运行 LangChain4j RAG 对照实验并产出迁移结论`

## 1. 结论

当前结论：`NEED_MORE_EVALUATION`

原因：

1. 已验证 LangChain4j adapter-only 路径可以在 test/eval 层接入当前 `KnowledgeSearchService`。
2. 已验证当前 RAG 结果可以转换为 LangChain4j `Content/TextSegment`，并保留 `documentId`、`filename`、`chunkIndex`、`citationId`、`source`、`visibility` 等 metadata。
3. 已验证 LangChain4j adapter-only runner 可以输出与 baseline runner 兼容的 `BaselineRagEvaluationResult`。
4. 尚未把 fixture seed 到真实 MySQL/Elasticsearch 后运行生产 `HybridKnowledgeSearchService` 的完整检索质量对照。
5. 因此目前不能判断 LangChain4j 在真实检索质量、延迟、版本过滤、生成忠实度上是否优于当前自研 RAG。

不得基于本报告直接替换生产 RAG 链路。

## 2. 环境

```text
OS: Windows
Shell: PowerShell
JDK target: 17
Maven command:
  mvn -pl yanban-knowledge -am '-Dtest=BaselineRagRunnerTest,LangChain4jAdapterRagRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试结果：

```text
BaselineRagRunnerTest: 3 tests, failures 0, errors 0
LangChain4jAdapterRagRunnerTest: 2 tests, failures 0, errors 0
Total: 5 tests, failures 0, errors 0
```

## 3. 依赖版本

新增依赖：

```text
dev.langchain4j:langchain4j-core:1.17.1
scope: test
```

依赖策略：

1. 只引入 `langchain4j-core`，没有引入 Spring Boot starter。
2. 依赖仅用于 test/eval spike，不进入生产运行链路。
3. 没有引入 Elasticsearch LangChain4j side index 依赖。

## 4. 数据集

Fixture 根目录：

```text
docs/evaluation/fixtures/rag-spike/
```

当前数据集：

```text
documents: 7
cases: 10
```

覆盖范围：

1. user A 私有论文原稿。
2. user A 私有润色稿。
3. user A 文献卡片。
4. user A 过时旧稿。
5. user A 已删除方法 note。
6. user B 私有 deadline note。
7. shared public RAG evaluation note。

Case 覆盖：

1. 私有知识召回。
2. 跨用户隔离。
3. public 文档召回。
4. ACTIVE/SUPERSEDED 版本优先级。
5. DELETED 文档过滤。
6. citation metadata 保留。
7. follow-up query。
8. 低置信度。
9. 论文原稿 vs 润色稿。
10. 中文查询召回。

## 5. Baseline 配置

已实现：

```text
BaselineRagRunner
BaselineSearchBackend
KnowledgeSearchServiceBaselineBackend
FixtureBackedBaselineSearchBackend
RagSpikeFixtureLoader
BaselineRagEvaluationResult
```

生产 baseline wrapper：

```text
KnowledgeSearchServiceBaselineBackend
  -> KnowledgeSearchService.search(query, userId, topK)
  -> BaselineRagHit
  -> BaselineRagEvaluationResult
```

当前测试验证的是：

1. fixture/case 可解析。
2. fixture-backed backend 可以跑完 10 个 case。
3. baseline result 可以写 JSON。
4. `KnowledgeSearchServiceBaselineBackend` 可以映射当前 `KnowledgeSearchResult`。

当前未验证的是：

1. 真实 MySQL/Elasticsearch ingestion。
2. 真实 `HybridKnowledgeSearchService` 对 10 个 fixture case 的质量。
3. 真实 embedding/vector 检索表现。

## 6. LangChain4j 配置

已实现：

```text
KnowledgeSearchServiceContentRetriever
LangChain4jAdapterRagRunner
LangChain4jAdapterRagRunnerTest
```

adapter-only 路径：

```text
KnowledgeSearchService
  -> KnowledgeSearchServiceContentRetriever
  -> LangChain4j Content/TextSegment
  -> LangChain4jAdapterRagRunner
  -> BaselineRagEvaluationResult compatible shape
```

已验证：

1. `KnowledgeSearchResult` 可以转换为 LangChain4j `Content`。
2. `TextSegment.metadata()` 可以保留自定义 metadata。
3. `Content.metadata()` 可以保留 score。
4. adapter runner 可以输出 `runner = langchain4j-adapter-only`。
5. adapter runner 输出的 case result 能保留 document id 和 citation id。

未实现：

1. LangChain4j `DefaultRetrievalAugmentor`。
2. LangChain4j `ContentInjector` 生成侧实验。
3. LangChain4j Elasticsearch EmbeddingStore side index。
4. Query transform 的真实模型调用。

## 7. Case 结果表

当前 case 级别状态：

| Case | 当前状态 | 说明 |
| --- | --- | --- |
| RAG-LC4J-001 | 未跑真实检索 | fixture 已定义，真实 DB/ES baseline 未执行 |
| RAG-LC4J-002 | 未跑真实检索 | fixture 已定义，真实跨用户隔离未执行 |
| RAG-LC4J-003 | 未跑真实检索 | fixture 已定义，真实 public 召回未执行 |
| RAG-LC4J-004 | 未跑真实检索 | fixture 已定义，真实版本排序未执行 |
| RAG-LC4J-005 | 未跑真实检索 | fixture 已定义，真实 DELETED 过滤未执行 |
| RAG-LC4J-006 | adapter smoke passed | metadata preservation 在 mock search result 上通过 |
| RAG-LC4J-007 | 未跑真实检索 | query transform 尚未实现 |
| RAG-LC4J-008 | 未跑真实检索 | 低置信度生成侧尚未实现 |
| RAG-LC4J-009 | 未跑真实检索 | 原稿/润色稿版本治理尚未实现 |
| RAG-LC4J-010 | 未跑真实检索 | 中文查询真实检索尚未执行 |

## 8. 指标汇总

当前可以确认的工程指标：

```text
new_dependency_count: 1
dependency_scope: test
runner_output_shape_compatible: true
metadata_mapping_smoke_test: pass
focused_tests: 5 passed
production_chain_modified: false
```

当前不能确认的质量指标：

```text
Recall@5: not measured against real DB/ES
MRR: not measured against real DB/ES
nDCG@5: not measured against real DB/ES
faithfulness: not measured
citation_coverage: not measured against real generation
source_correctness: not measured against real generation
latency_ms_p95: not measured
```

## 9. 失败和缺口

没有测试失败。

仍存在以下缺口：

1. 需要把 `documents.json` 和 fixture markdown seed 到 H2/MySQL test database。
2. 需要按 `versionStatus` 扩展或模拟知识库文档版本字段，否则无法真实验证 ACTIVE/SUPERSEDED/DELETED。
3. 需要真实 Elasticsearch 或测试替身来验证 hybrid recall。
4. 需要把 baseline runner 从 smoke test 推进到可输出完整 case result 的命令或集成测试。
5. 需要引入生成侧统一 answer prompt，才能评估 faithfulness 和 citation coverage。

## 10. 权限和版本过滤验证

已验证：

1. fixture-backed backend 可以避免 forbidden hit。
2. adapter metadata 中可以携带 `visibility`。

未验证：

1. 真实 `HybridKnowledgeSearchService` 对 user A/user B 的隔离。
2. 真实 index 中 `DELETED` 文档过滤。
3. 真实 ACTIVE/SUPERSEDED 排序。

风险：

当前 `KbDocument` 实体尚未包含 `versionStatus`、`lineageId`、`projectId` 等字段。即使 LangChain4j adapter 能保留 metadata，业务层仍需要先补齐版本治理字段，否则无法完成真正的版本过滤。

## 11. 引用元数据验证

已验证：

1. `KnowledgeSearchServiceContentRetriever` 能把 `KnowledgeSearchResult.citationId()` 写入 `TextSegment.metadata()`。
2. `LangChain4jAdapterRagRunner` 能从 `TextSegment.metadata()` 还原 citation id。

未验证：

1. 生成回答是否正确引用 citation id。
2. 多 chunk、多文档时 citation coverage 是否稳定。
3. LangChain4j `ContentInjector` 默认格式是否符合当前产品引用展示要求。

## 12. 性能和复杂度分析

复杂度变化：

1. 新增 test-scope dependency 1 个。
2. 新增 adapter 类 1 个。
3. 新增 runner 类 1 个。
4. 没有引入生产配置。
5. 没有引入 LangChain4j Elasticsearch side index。

性能：

1. 当前 adapter-only 层没有额外网络调用。
2. 当前测试未测真实 p50/p95 latency。
3. 后续如果启用 query transform，会增加模型调用成本和延迟。

## 13. 迁移建议

短期建议：

1. 保留当前生产 RAG。
2. 保留 LangChain4j adapter-only runner 在 test/eval 层。
3. 不接入生产聊天链路。
4. 下一步先补齐真实 fixture seeding 和生产 baseline eval。

下一步 issue 建议：

1. `eval: 将 RAG spike fixture seed 到测试数据库并运行真实 baseline`
2. `implementation: 为知识库文档预留 versionStatus/lineageId/projectId 测试字段或评测映射`
3. `eval: 增加生成侧 answer prompt 和 citation coverage 评测`

## 14. 最终判断

```text
NEED_MORE_EVALUATION
```

解释：

LangChain4j adapter-only 路径在工程接入层面可行，且可以保留关键 metadata；但当前实验只证明 adapter smoke test 和统一输出结构，没有证明真实检索质量优于 baseline，也没有证明生成忠实度和 citation coverage 更好。

因此，本项目下一步应继续做真实 baseline eval，而不是直接把生产 RAG 切到 LangChain4j。
