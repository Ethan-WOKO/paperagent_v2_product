# RAG Spike Fixture

本目录为 LangChain4j RAG 对照实验提供固定样本和评测用例。

## 文件结构

```text
docs/测试资产/测试样例/rag-spike/
  documents.json
  cases.json
  user-a/
  user-b/
  shared/
```

## 用户约定

```text
user-a: userId = 101
user-b: userId = 202
```

`user-a` 和 `user-b` 的私有文档必须互相隔离。`shared` 目录中的 public 文档可以被两个用户检索。

## 文档状态

`documents.json` 中的 `versionStatus` 用于模拟后续知识版本治理：

```text
ACTIVE
SUPERSEDED
DELETED
```

RAG runner 需要遵守以下规则：

1. `ACTIVE` 可以进入检索结果。
2. `SUPERSEDED` 可以作为低优先级候选，但应排在同 lineage 的 `ACTIVE` 后。
3. `DELETED` 不得进入检索结果，也不得注入模型上下文。

## Case 字段

`cases.json` 使用半结构化格式，后续 runner 应至少读取以下字段：

```text
caseId
area
query
userId
projectId
topK
expectedDocumentIds
forbiddenDocumentIds
expectedCitationIds
expectedAnswerFacts
```

可选字段：

```text
previousContext
rankingRules
notes
```

## 人工核对方式

在 runner 尚未实现前，可以人工检查：

1. `expectedDocumentIds` 是否能从 fixture 文本中找到支撑事实。
2. `forbiddenDocumentIds` 是否包含跨用户、`DELETED` 或不应优先的文档。
3. `expectedCitationIds` 是否在 `documents.json` 中存在。
4. `expectedAnswerFacts` 是否能被检索到的文本直接支持。

## 后续实现约束

1. runner 不得把 `documents.json` 中 `visibility = PRIVATE` 的其他用户文档写入当前用户可见结果。
2. runner 不得把 `versionStatus = DELETED` 的文档写入检索结果。
3. runner 必须保留 `documentId`、`filename`、`chunkIndex`、`citationId`、`sourceType` 等 metadata。
4. LangChain4j adapter-only runner 必须和 baseline runner 使用同一批 case。

## 检索策略基线

在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\测试资产\执行脚本\当前可用\run-rag-retrieval-baseline.ps1
```

脚本使用真实 Elasticsearch 和固定的四维测试向量函数，对同一批 case 比较：

```text
BM25
KNN
BM25 + KNN + RRF
BM25 + KNN + RRF + 规则重排
```

报告输出到 `yanban-knowledge/target/rag-eval/`，包含 Recall@1/3/5/10、MRR@10、
nDCG@1/3/5/10、单次本地检索 P50/P95、权限违规命中数和逐 case 差异。

当前 fixture 只有 10 个 case，其中 7 个含相关文档标注。它用于验证实验代码和安全过滤，
不代表生产检索质量，也不能直接作为对外效果数据。正式比较 Embedding 或重排模型时，必须保持
case 和相关性标注冻结，改用真实 Embedding，并扩大盲测集。

## BEIR SciFact 标准基线

同一脚本会下载并校验固定 MD5 的 BEIR SciFact 数据包，在完整 5,183 篇摘要上评测。
文档和查询向量缓存到 `yanban-knowledge/target/rag-eval/cache/`，Elasticsearch 评测索引会保留复用，
并生成固定且相互嵌套的 50、100、300 问题报告。数据集、向量、索引和报告都是本地测试产物，
不进入 Git。

```powershell
powershell -ExecutionPolicy Bypass -File `
  .\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 `
  -MaxQueries 50
```

日常开发使用 `-MaxQueries 50`，阶段比较使用 `100`，冻结的最终结果使用 `300`。
三档始终使用完整语料，只减少查询数，避免因为删除干扰文档造成指标虚高。
脚本从进程环境或仓库本地 `.env` 读取 `DASHSCOPE_API_KEY`，不会把密钥写入报告。
当前标准配置使用 `text-embedding-v4` 的 1,024 维向量、每路 50 个候选、RRF 常数 60，
最终计算 Top 10 的 MRR 和 nDCG，并记录 Recall@1/3/5/10/20/50；Recall@20/50 用于判断
后续重排模型能够达到的候选召回上限。更换模型或维度会使用独立索引，避免复用旧向量。

需要比较真实模型重排时增加 `-IncludeModelRerank`。评测保持查询、语料和 RRF Top50
候选完全一致，分别让 `qwen3-rerank` 重排前 20 和前 50；Top20 模式会原样保留
第 21～50 名。模型响应按候选内容摘要缓存，另行输出请求数、缓存命中、输入 token
和接口耗时，便于同时比较质量与成本。

### 训练、消融和冻结测试

所有参数只使用 SciFact 的 809 条训练查询选择，正式 300 条测试查询只运行一次冻结配置，
不得根据测试结果继续调参：

```powershell
# 600 条训练集网格搜索，剩余 209 条训练集验证
.\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 -TuneRrf

# 独立 50 条训练查询比较 rerank instruct
.\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 -EvaluateRerankIntents

# 独立 50 条训练查询比较受控 Query 改写权重
.\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 -EvaluateQueryRewrite

# 比较 RRF、Query 改写与模型重排的组合效果
.\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 -EvaluateOptimizedPipeline

# 在未参与调参的正式 300 题上验证冻结配置
.\docs\测试资产\执行脚本\当前可用\run-scifact-retrieval-baseline.ps1 -EvaluateFrozenFinal
```

训练集选出的配置为 lexical weight `0.5`、vector weight `1.0`、RRF rank constant `10`、
Top50 后调用 `qwen3-rerank`，并保留供应商 API 默认 instruct。固定 SciFact 专用 instruct
在训练集消融中低于默认 instruct，因此不能作为通用科研检索默认值。受控 Query 改写会保留
原查询、数字和否定极性，最多生成两个改写，但在模型重排后的组合消融中没有继续提升，
因此不进入冻结配置。

冻结 300 题结果：等权 RRF + 模型重排的 MRR@10 / nDCG@10 / Recall@50 为
`0.7584 / 0.7930 / 0.9460`；训练集选出的加权 RRF + 模型重排为
`0.7593 / 0.7946 / 0.9560`。两个方案均没有权限违规命中。测试报告和模型缓存位于
`yanban-knowledge/target/rag-eval/`，属于本地生成资产，不提交 Git。
