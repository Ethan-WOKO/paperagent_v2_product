# 研伴 Agent OCR 接入说明

> 文档分类：**待重新评估**。当前 V2 PDF/DOCX 工具明确不执行 OCR；只有扫描文档或图表需求形成独立权限和预算合同时才接入。

> 本文档说明图片类知识库文档的 OCR 扩展方式。当前 OCR 属于可插拔能力，第二期继续完善。

## 1. 目标

当上传图片文件时，知识库处理链路应：

1. 判断 `mimeType` 是否为 `image/*`。
2. 调用配置的 `OcrProvider`。
3. 将 OCR 文本作为普通文本进入分块、embedding、索引流程。
4. OCR 未配置或失败时，文档状态置为 `FAILED`，并写入错误信息。

## 2. Provider 抽象

当前最小抽象：

```text
OcrProvider
  -> recognize(image bytes, mimeType)
```

实现可以是：

- HTTP OCR 网关
- 本地 OCR 命令
- 云厂商 OCR SDK

## 3. HTTP OCR 网关建议协议

请求：

```http
POST /ocr
Content-Type: multipart/form-data

file=<image>
mimeType=image/png
```

响应：

```json
{
  "text": "识别出的文本内容"
}
```

错误：

```json
{
  "error": "OCR failed"
}
```

## 4. 配置建议

```yaml
yanban:
  knowledge:
    ocr:
      enabled: true
      provider: http
      api-url: http://localhost:9009/ocr
      api-key: ${YANBAN_OCR_API_KEY:}
      timeout: 30s
```

实际字段以代码实现为准，第二期需将配置文档与实现完全对齐。

## 5. 失败处理

| 场景 | 行为 |
|---|---|
| 未配置 OCR | 文档置为 FAILED，错误为 OCR 未配置 |
| OCR 服务超时 | 文档置为 FAILED，记录超时信息 |
| OCR 返回空文本 | 文档置为 FAILED 或 READY 空内容，第二期需固定策略 |
| OCR 成功 | 文本进入正常 KB 分块与索引 |

## 6. 测试建议

自动化：

- Mock OCR 返回固定文本，验证 `kb_chunks` 包含该文本。
- OCR 未配置时上传 png，验证状态为 FAILED。
- OCR 超时时记录错误信息。

手动：

- 上传一张包含中文段落的 png。
- 等待状态 READY。
- 在检索调试页搜索图片中的关键词。

## 7. 第二期待完善

- 增加本地 OCR 示例实现。
- 增加 HTTP OCR 网关示例项目或脚本。
- 支持 OCR 结果预览。
- 支持失败重试。
