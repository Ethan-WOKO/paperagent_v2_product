# API 冒烟记录

> 文档分类：**待重新评估**。执行前必须对照当前 V2、Project 和生产路由，不能把旧端点结果当作当前验收。

## 认证

### 注册

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
```

预期：`201 Created`，返回 `tokenType`、`accessToken`、`refreshToken`、`expiresIn`。

重复用户名注册预期：`409 Conflict`。

### 登录

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
```

预期：`200 OK`，返回 `tokenType`、`accessToken`、`refreshToken`、`expiresIn`。

### 当前用户

```bash
TOKEN="替换为 accessToken"
curl -i http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer $TOKEN"
```

预期：`200 OK`，返回当前用户 id 与 username。

未带 Token 或伪造 Token 预期：`401 Unauthorized`。

## Agent 会话

以下示例复用上文 `TOKEN`。

### 创建会话

```bash
curl -i -X POST http://localhost:8080/api/v1/agent/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试会话","maxSteps":20}'
```

预期：`201 Created`，返回会话 `id`、模型快照、`maxSteps`。

### 会话列表

```bash
curl -i http://localhost:8080/api/v1/agent/sessions \
  -H "Authorization: Bearer $TOKEN"
```

预期：`200 OK`，仅返回当前用户自己的会话。

### 发送消息

```bash
SESSION_ID="替换为会话 id"
curl -i -X POST http://localhost:8080/api/v1/agent/sessions/$SESSION_ID/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"你好"}'
```

预期：`200 OK`，返回 `success`、`assistantContent`、本轮持久化消息列表。

### 查询消息历史

```bash
curl -i http://localhost:8080/api/v1/agent/sessions/$SESSION_ID/messages \
  -H "Authorization: Bearer $TOKEN"
```

预期：`200 OK`，返回该会话消息历史。访问其他用户会话预期：`404 Not Found`。

## 知识库（阶段 A 临时接口）

### 简化上传

```bash
curl -i -X POST http://localhost:8080/api/v1/kb/documents/simple-upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./notes.md" \
  -F "isPublic=false"
```

预期：`201 Created`，返回文档 `id`、`status=READY`。

### 检索调试

```bash
curl -i -X POST http://localhost:8080/api/v1/search \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"alpha","topK":5}'
```

预期：`200 OK`，返回当前用户可见的 chunk 列表。若文档为他人私有，则不应命中；若文档为公开，则其他用户可命中。

## WebSocket 对话（阶段 A）

连接地址：

```text
ws://localhost:8080/api/v1/ws/chat?token=<accessToken>
```

发送示例：

```json
{"sessionId":1,"content":"你好","ragDisabled":false,"skillId":null}
```

预期：服务端推送多条 `chunk` 事件，最后推送 `done`；同时会把本轮 user/assistant 消息持久化到对应会话。前端 `/chat` 页已按该协议接入。详见 `docs/待重新评估/WEBSOCKET.md`。

## 用户设置（阶段 A）

获取当前用户设置：

```bash
curl -X GET http://localhost:8080/api/v1/settings \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

更新设置：

```bash
curl -X PUT http://localhost:8080/api/v1/settings \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "defaultProvider": "deepseek",
    "deepseekApiKey": "sk-xxxx",
    "deepseekModel": "deepseek-chat",
    "deepseekTemperature": 0.7,
    "maxSteps": 5,
    "ragDefaultEnabled": true
  }'
```

预期：`GET /api/v1/settings` 不返回明文 `deepseekApiKey`，仅返回 `deepseekApiKeyConfigured=true/false`；更新 `maxSteps` 与 `ragDefaultEnabled` 后，新建会话会继承这些默认值。
