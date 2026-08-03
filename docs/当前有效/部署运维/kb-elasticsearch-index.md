# 知识库 Elasticsearch 索引与重建策略

> 关联 issue: #28 `implementation: 更新 Elasticsearch index mapping 与重建策略文档`

## 1. 事实源边界

MySQL `kb_documents` 和 `kb_chunks` 仍是知识库事实源。Elasticsearch 只负责 candidate recall，可删除、可重建，不作为权限、版本或 Project 治理的最终事实源。

检索链路必须保持两层过滤：

1. ES 查询阶段按 `userId/isPublic/versionStatus/projectId` 做粗过滤，减少错误召回。
2. ES hit 回 MySQL hydrate 后，再由 `KnowledgeDocumentSearchPolicy` 二次确认 `READY`、用户可见、Project 预留规则和版本状态。

## 2. 当前索引名

当前配置默认：

```text
yanban.knowledge.elasticsearch.index-name=yanban-kb-chunks-v1
```

本阶段不直接改默认索引名，避免运行环境突然失去旧索引。后续需要重建时，推荐创建新物理索引并切 alias。

## 3. 推荐 mapping

后续新索引至少包含：

```json
{
  "mappings": {
    "properties": {
      "chunkId": { "type": "long" },
      "documentId": { "type": "long" },
      "userId": { "type": "long" },
      "projectId": { "type": "long" },
      "isPublic": { "type": "boolean" },
      "sourceType": { "type": "keyword" },
      "versionStatus": { "type": "keyword" },
      "lineageId": { "type": "keyword" },
      "versionNo": { "type": "integer" },
      "canonicalKey": { "type": "keyword" },
      "chunkIndex": { "type": "integer" },
      "text": { "type": "text" },
      "vector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      }
    }
  }
}
```

`dims` 必须和 `yanban.knowledge.elasticsearch.vector-dimensions` 保持一致。

## 4. 查询过滤要求

默认查询：

```text
versionStatus IN ['ACTIVE']
visible = userId == currentUserId OR isPublic == true
project filter omitted when currentProjectId is null
```

显式历史版本查询：

```text
versionStatus IN ['ACTIVE', 'SUPERSEDED']
```

即使 ES 返回了 `DELETED` 或 `ARCHIVED`，MySQL hydrate 后也必须丢弃，不得注入模型上下文。

## 5. 重建策略

推荐 alias 策略：

```text
yanban-kb-chunks-v1-20260704  <- physical index
yanban-kb-chunks-v1           <- read/write alias
```

重建步骤：

1. 创建新物理索引，例如 `yanban-kb-chunks-v1-20260704`。
2. 按推荐 mapping 创建字段。
3. 从 MySQL 查询 `kb_documents.status='READY'` 的文档和 chunks。
4. 对每个 chunk 重新计算 embedding，写入 ES，并携带本文档第 3 节 metadata。
5. 抽样运行 RAG eval，确认 user/version/project 过滤行为。
6. 将 alias 原子切到新物理索引。
7. 保留旧索引一段时间用于回滚。

## 6. 非目标

1. 不把权限事实源迁移到 ES。
2. 不在本阶段引入 LangChain4j Elasticsearch side index。
3. 不物理删除 MySQL 中的历史文档。
4. 不实现完整 Project/Workspace。
