# WebSocket 对话协议（阶段 A）

> 文档分类：**待重新评估**。工作区 `/chat` 已恢复，Project 页面保持 V2-only；本文的协议适用范围需要按当前路由重新核验。

端点：

```text
ws://localhost:8080/api/v1/ws/chat?token=<accessToken>
```

## 客户端发送

```json
{
  "sessionId": 1,
  "content": "你好",
  "ragDisabled": false,
  "skillId": null
}
```

说明：

- `sessionId`：已有会话 ID
- `content`：本轮用户输入
- `ragDisabled`：本轮是否禁用知识库
- `skillId`：阶段 A 暂未使用，可传 `null`

## 服务端事件

### chunk

```json
{
  "type": "chunk",
  "content": "你",
  "sessionId": 1,
  "error": null,
  "finishReason": null
}
```

### done

```json
{
  "type": "done",
  "content": null,
  "sessionId": 1,
  "error": null,
  "finishReason": "stop"
}
```

### error

```json
{
  "type": "error",
  "content": null,
  "sessionId": 1,
  "error": "错误信息",
  "finishReason": null
}
```
