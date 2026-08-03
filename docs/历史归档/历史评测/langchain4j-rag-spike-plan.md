# LangChain4j RAG 对照实验规划

> 关联 issue: #6 `spike: 规划 LangChain4j RAG 对照实验`
>
> 本文定义 LangChain4j RAG spike 的实验范围、对照基线、样本集、指标、集成边界和准入标准。当前 PR 只做 spike 规划，不引入依赖，不替换现有 RAG。

## 1. 结论先行

LangChain4j 可以进入 RAG spike，但不能直接替换当前自研 RAG 链路。

首个 spike 应采用“双轨对照”：

1. 保留当前 `KnowledgeSearchService -> KnowledgeSearchContextProvider -> HarnessEngine` 作为 baseline。
2. 新增实验性 LangChain4j RAG runner，只在测试和本地 profile 中启用。
3. 使用同一批知识库样本、同一批 query、同一套指标比较检索质量和生成忠实度。
4. 只要用户隔离、版本过滤、引用元数据任一项无法保留，就不得进入生产替换。

## 2. 官方能力依据

本 spike 规划参考以下官方或一手来源：

1. LangChain4j 官方 Get Started：当前文档要求 JDK 17，Maven 依赖版本示例为 `1.17.1`，并说明每个集成有独立 Maven 依赖。<https://docs.langchain4j.dev/get-started/>
2. LangChain4j 官方 RAG 教程：RAG 分为 indexing 和 retrieval 两个阶段；Advanced RAG 包含 `QueryTransformer`、`QueryRouter`、`ContentRetriever`、`ContentAggregator`、`ContentInjector` 等组件。<https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/rag.md>
3. LangChain4j 官方 Embedding Stores 页面：支持多种 embedding store，其中包含 Elasticsearch。<https://docs.langchain4j.dev/category/embedding-stores/>
4. Maven Central：`dev.langchain4j:langchain4j` 当前可用版本为 `1.17.1`。<https://central.sonatype.com/artifact/dev.langchain4j/langchain4j>
5. Maven Central：`langchain4j-elasticsearch-spring-boot4-starter` 仍是 beta 系列，且依赖 `langchain4j-elasticsearch`。<https://central.sonatype.com/artifact/dev.langchain4j/langchain4j-elasticsearch-spring-boot4-starter/1.17.0-beta27>

风险提示：

1. LangChain4j BOM 虽然是 `1.17.1`，但官方文档提示很多模块仍可能是 `1.17.1-beta27`，后续实现必须锁版本并记录兼容风险。
2. Elasticsearch Spring Boot starter 当前显示 Spring Boot 4 依赖，当前项目如果仍在 Spring Boot 3 体系，不能直接引入 starter；应优先用 core 模块或手动配置，避免依赖冲突。

## 3. 当前自研 RAG 基线

当前链路：

```text
FileProcessingService / KnowledgeIngestionService
  -> splitText(documentId, text)
  -> VectorizationService
  -> EmbeddingClient
  -> KnowledgeIndexService / ElasticsearchKnowledgeSearchIndexClient
  -> HybridKnowledgeSearchService
  -> KnowledgeSearchContextProvider
  -> HarnessEngine.buildKnowledgeContext(...)
```

已具备能力：

1. 文档经 Tika 或 OCR 抽取文本。
2. 文本按固定 500 字符切块。
3. 每个 chunk 生成 embedding。
4. Elasticsearch 中保存 vector 和文本。
5. 查询时使用 `EmbeddingClient.embed(query)`。
6. `HybridKnowledgeSearchService` 通过 query variants 做多路召回。
7. Elasticsearch 查询中已有 `userId` 或 `isPublic` 权限过滤。
8. 查询结果回查 MySQL `KbDocument`，再做 rerank。
9. `KnowledgeSearchContextProvider` 输出 `KnowledgeSnippet`，保留 citationId、scoreBand、source、rerankScore、rerankReason。

明显短板：

1. chunking 固定字符长度，缺少语义边界。
2. 文档版本、论文原稿/润色稿 lineage、过时版本降权尚未完善。
3. 生成侧 citation 格式和 evidence trace 还不够稳定。
4. RAG eval runner 尚未真正自动化。
5. 检索侧和生成侧的职责边界不够清晰。
6. 对 follow-up query 的上下文改写能力有限。

## 4. Spike 非目标

1. 不替换生产 RAG。
2. 不改默认聊天链路。
3. 不迁移现有 Elasticsearch index。
4. 不改知识库上传流程。
5. 不引入完整 LangChain4j Agent Runtime。
6. 不引入 LangChain4j ChatMemory，ChatMemory 是 RAG spike 之后的单独议题。
7. 不把 Project 功能提前做进来，只预留 nullable `projectId` 过滤参数。

## 5. 实验问题

Spike 必须回答：

1. LangChain4j RAG 的检索质量是否优于当前 `HybridKnowledgeSearchService`？
2. LangChain4j 是否能保留用户级权限过滤？
3. LangChain4j 是否能保留文档版本、source、citationId、chunkIndex 等引用元数据？
4. LangChain4j 的 query transform 是否能改善 follow-up 问题召回？
5. LangChain4j 的 content injection 是否能减少生成幻觉或引用错误？
6. 引入 LangChain4j 会增加还是减少接入复杂度？
7. 最终应该保留自研 RAG、部分迁移，还是分阶段替换？

## 6. 实验架构

### 6.1 Baseline Runner

调用当前生产链路，但只在测试环境运行：

```text
BaselineRagRunner
  -> KnowledgeSearchService.search(query, userId, topK)
  -> KnowledgeSearchContextProvider.searchContext(...)
  -> 当前 HarnessEngine 的 knowledge context 格式
  -> 统一 answer prompt
```

### 6.2 LangChain4j Adapter Runner

第一阶段优先做 adapter-only，不重新索引：

```text
LangChain4jAdapterRagRunner
  -> Custom ContentRetriever
       -> 调用现有 KnowledgeSearchService
       -> 转换为 LangChain4j Content/TextSegment
       -> 写入 metadata: userId, documentId, filename, chunkIndex, citationId, source
  -> DefaultRetrievalAugmentor 或自定义 RetrievalAugmentor
  -> 统一 answer prompt
```

目的：

1. 隔离“LangChain4j retrieval augmentor/content injector 是否有价值”。
2. 不让 embedding store 差异影响第一轮判断。
3. 先验证 metadata、引用、权限和上下文注入边界。

### 6.3 LangChain4j EmbeddingStore Runner

第二阶段再做 side index：

```text
LangChain4jEmbeddingStoreRagRunner
  -> 使用同一批 KbChunk
  -> 使用同一个 EmbeddingClient 或等价 LangChain4j EmbeddingModel adapter
  -> 写入实验 index: yanban-kb-langchain4j-spike-v1
  -> 使用 LangChain4j Elasticsearch EmbeddingStore / ContentRetriever
  -> 使用 dynamicFilter 保留 userId/projectId/version/status 过滤
```

约束：

1. 使用独立实验 index，不污染当前生产 index。
2. 实验 index 可删除重建。
3. 不改现有 `KnowledgeIndexService`。
4. 如果依赖与 Spring Boot 版本冲突，退回到 adapter-only 方案。

## 7. 依赖策略

首选：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-bom</artifactId>
      <version>1.17.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

候选依赖：

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j</artifactId>
</dependency>

<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-elasticsearch</artifactId>
</dependency>
```

实施前必须确认：

1. 当前项目 JDK 版本满足 LangChain4j 最低 JDK 17。
2. `langchain4j-elasticsearch` 版本是否与项目 Elasticsearch client 兼容。
3. 不直接引入 Spring Boot 4 starter 到当前 Spring Boot 3 项目。
4. beta 模块需要锁版本，不能使用浮动版本。

## 8. 样本集

使用固定 fixture，保证 baseline 和 LangChain4j 使用同一批数据。

建议新增：

```text
docs/evaluation/fixtures/rag-spike/
  user-a/
    active-paper-original.md
    active-paper-polished.md
    literature-card-graph-rag.md
    outdated-draft.md
  user-b/
    private-deadline.md
  shared/
    public-rag-note.md
```

样本覆盖：

1. 用户 A 私有论文原稿。
2. 用户 A 私有润色稿。
3. 用户 A 文献卡片。
4. 用户 A 过时旧稿。
5. 用户 B 私有文档，用于权限泄漏测试。
6. public 文档，用于公开知识检索测试。

必须标注 metadata：

```text
documentId
userId
projectId nullable
sourceType
versionStatus: ACTIVE / SUPERSEDED / DELETED
lineageId
filename
chunkIndex
citationId
```

## 9. Eval Case

最低 case：

| Case ID | 目标 | 期望 |
| --- | --- | --- |
| RAG-LC4J-001 | 私有知识召回 | 用户 A 能检索到自己的 ACTIVE 文档 |
| RAG-LC4J-002 | 权限隔离 | 用户 B 不能检索用户 A 私有文档 |
| RAG-LC4J-003 | public 文档 | 用户 A/B 都能检索 public 文档 |
| RAG-LC4J-004 | 过时版本降权 | ACTIVE 版本排在 SUPERSEDED 前 |
| RAG-LC4J-005 | 删除版本过滤 | DELETED 文档不得进入结果 |
| RAG-LC4J-006 | 引用元数据 | answer citation 能对应 citationId/filename/chunkIndex |
| RAG-LC4J-007 | Follow-up query | 指代型追问能通过 query transform 找到正确上下文 |
| RAG-LC4J-008 | 低置信度 | 无相关证据时明确说证据不足 |
| RAG-LC4J-009 | 论文原稿 vs 润色稿 | 润色稿优先于旧原稿，除非用户明确问原稿 |
| RAG-LC4J-010 | 中文查询 | 中文 query 能召回英文/中英混合资料中的相关内容 |

每个 case 记录：

```text
query
userId
projectId
expectedDocumentIds
forbiddenDocumentIds
expectedCitationIds
expectedAnswerFacts
```

## 10. 指标

检索指标：

```text
Recall@5
MRR
nDCG@5
forbidden_hit_count
active_version_rank
metadata_preservation_rate
latency_ms_p50
latency_ms_p95
```

生成指标：

```text
faithfulness
citation_coverage
source_correctness
unsupported_claim_count
answer_completeness
```

工程指标：

```text
new_dependency_count
lines_of_adapter_code
config_complexity
index_rebuild_time
test_runtime
failure_modes
```

最低通过门槛：

1. `forbidden_hit_count = 0`。
2. `metadata_preservation_rate >= 95%`。
3. LangChain4j 方案 Recall@5 不低于 baseline。
4. LangChain4j 方案 faithfulness 不低于 baseline。
5. 不能牺牲用户隔离、版本过滤、citation metadata。
6. p95 latency 不超过 baseline 的 1.5 倍，除非质量提升明显且用户体验可接受。

## 11. 输出报告格式

Spike 实现 PR 必须输出：

```text
docs/evaluation/reports/langchain4j-rag-spike-YYYYMMDD.md
```

报告结构：

```text
环境
依赖版本
数据集
Baseline 配置
LangChain4j 配置
Case 结果表
指标汇总
失败 case 分析
权限和版本过滤验证
引用元数据验证
性能和复杂度分析
结论
后续建议
```

结论必须是以下之一：

```text
KEEP_CURRENT_RAG
ADOPT_LANGCHAIN4J_ADAPTER_ONLY
ADOPT_LANGCHAIN4J_RETRIEVAL_COMPONENTS
ADOPT_LANGCHAIN4J_INDEX_AND_RETRIEVAL
NEED_MORE_EVALUATION
```

## 12. 后续实现 issue 建议

1. `implementation: 新增 RAG spike fixture 和 eval case 定义`。
2. `implementation: 新增 BaselineRagRunner 和结果记录格式`。
3. `implementation: 新增 LangChain4j adapter-only RAG runner`。
4. `implementation: 运行 LangChain4j RAG 对照实验并输出报告`。
5. `decision: 根据 RAG spike 结果决定迁移路径`。

如果 adapter-only 结果明显无收益，则不继续做 EmbeddingStore side index。

如果 adapter-only 有收益但 side index 依赖冲突较大，则优先采用 `Custom ContentRetriever + 当前 KnowledgeSearchService` 的混合路径。

## 13. 风险

1. LangChain4j beta 模块带来 API 变动。
2. Elasticsearch starter 可能引入 Spring Boot 4 依赖冲突。
3. 直接使用 LangChain4j EmbeddingStore 可能丢失当前 MySQL hydrate 和权限逻辑。
4. Easy RAG 可能绕过我们对版本、权限和引用元数据的治理。
5. Query transform 引入额外模型调用，增加延迟和成本。
6. 生成侧看起来更流畅，但 citation correctness 可能下降。

## 14. 禁止事项

1. 禁止未跑对照 eval 就替换生产 RAG。
2. 禁止把用户权限过滤只交给模型 prompt。
3. 禁止把 private/public 过滤放在生成后处理阶段。
4. 禁止让 LangChain4j side index 复用生产 index 名称。
5. 禁止在 spike 中删除或重写现有 `KnowledgeSearchService`。
6. 禁止只用主观体验评价 RAG 质量。
