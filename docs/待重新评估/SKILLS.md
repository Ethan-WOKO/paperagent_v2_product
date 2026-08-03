# 研伴 Agent Skills 使用说明

> 文档分类：**待重新评估**。后续必须区分产品 Skill、Codex Skill 和 V2 product tool catalog，避免把框架能力误认为 V2 权威注册。

> 当前产品边界：本文中的 Harness、`mcp_fs__*` 和 filesystem roots 属于 legacy 说明，不适用于 Project V2。Project V2 只能通过冻结 ProjectVersion 与隔离 Workspace 工具访问用户上传内容；Skill 的 V2 工具约束与恢复合同尚待实施。

> 本文档说明第二期 Skills 体系的使用、编写与排错方式。

## 1. Skill 定位

Skill 是一种“工作方式说明 + 工具白名单”，不是独立插件服务。它通过向 Harness 注入 system prompt，并限制模型可见工具来改变对话行为。

适合 Skill 的场景：

- code review
- 文档总结
- 项目分析
- 固定格式报告生成

不适合 Skill 的场景：

- 知识库上传管理
- 论文修改固定流程
- 需要复杂前端交互的业务功能

## 2. 目录结构

内置 Skill：

```text
skills/builtin/<skill-id>/
  SKILL.md
  skill.yaml
```

用户 Skill：

```text
skills/user/<skill-id>/
  SKILL.md
  skill.yaml
```

## 3. SKILL.md

`SKILL.md` 是主要提示词文件，建议包含：

- 角色定义
- 输入要求
- 可用工具说明
- 禁止事项
- 最终输出格式

示例：

```md
你是一个代码审查助手。
当用户提供文件路径时，优先调用 filesystem MCP 工具读取文件。
最终输出：总体结论、严重问题、一般问题、改进建议。
```

## 4. skill.yaml

推荐字段：

```yaml
name: code-review
description: 使用 filesystem MCP 读取本地文件并进行结构化代码审查
allowed_tools:
  - mcp_fs__read_file
  - mcp_fs__list_directory
  - mcp_fs__read_multiple_files
```

字段说明：

| 字段 | 说明 |
|---|---|
| name | 展示名称 |
| description | 设置页与选择框说明 |
| allowed_tools | 允许模型调用的工具名 |

## 5. 工具白名单

Skill 启用后，Harness 只暴露 `allowed_tools` 中列出的工具。

注意：

- 工具名必须是真实注册到 ToolRegistry 的名称。
- MCP 工具通常带前缀，例如 `mcp_fs__read_file`。
- 如果 MCP server 未成功注册，模型无法真正调用工具。

## 6. 常见问题

### 6.1 模型输出伪 `<tool_call>` 文本

通常原因：

1. 模型没有收到真实 tools schema。
2. MCP 工具注册失败。
3. Prompt 误导模型输出伪协议。
4. Provider 不支持或没有正确解析 tool_calls。

排查：

- 看启动日志是否有 `Registered xx MCP tools for FILESYSTEM`。
- 看 Harness 日志中的 `toolsVisible`。
- 看模型响应是否有结构化 `tool_calls`。

### 6.2 filesystem 路径被拒绝

原因通常是路径不在 `filesystemRoots` 白名单中。

处理方式：

- 到设置页配置项目根目录。
- 避免配置整个磁盘根目录。
- 推荐配置具体项目目录。

### 6.3 Skill 不出现在下拉框

检查：

- 目录是否在 `skills/builtin/` 或 `skills/user/` 下。
- 是否存在 `SKILL.md`。
- 是否被设置页禁用。
- 是否调用过 refresh API 或重启后端。

## 7. 第二期待完善

- Skill 自动推荐。
- Skill 诊断面板。
- 更多内置 Skill 示例。
- 更严格的 skill.yaml 校验。
