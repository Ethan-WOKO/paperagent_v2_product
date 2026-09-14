# Knowledge Document HTTP Contract

日期：2026-09-14。接口仍需现有成员/DEMO JWT，匿名 401。

## GET /api/v1/kb/documents

原字段保留，新增 ownedByCurrentUser、administratorPublic、downloadAvailable。
本人原列表 UNION 当前有效 ADMIN 的公开 READY/ACTIVE/未删除文档，updatedAt desc/id desc 去重排序，空集合 []。objectKey 不返回，非本人 errorMessage 置空。

## GET /api/v1/kb/documents/{documentId}/download

- 200 原字节流；application/octet-stream；attachment UTF-8 安全文件名；Cache-Control private, no-store；X-Content-Type-Options nosniff。
- 身份不可由 query/body 提供。先排除删除/ARCHIVED，再检查 owner 或管理员公开 READY/ACTIVE，最后访问存储。
- 401 未登录/身份失效。
- 404 无权限、不存在、删除/归档、无 objectKey 或 NoSuchKey，不暴露存储路径。
- 503 存储故障；流开始后故障中断传输并关闭资源，不把异常作为文件内容。

列表 downloadAvailable 不是授权令牌，直取重新读取最新权限。列表不查询 MinIO，因此远端文件丢失可在点击时返回 404。

## Unchanged

preview/delete 仍 owner-only，DEMO_SEED 保护不变。上传不变。POST /search 保持原有 owner OR public 检索，LLM/RAG/工具入口不变。SecurityConfig 无新增匿名规则。
