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
