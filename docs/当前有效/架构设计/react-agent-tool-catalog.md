# ReAct Agent 工具目录边界

状态：**CURRENT**  
日期：2026-08-17  
适用范围：`agent-engine-reactplan` 与产品内部注册工具网关

## 决定

ReAct Engine 不复制产品工具实现。产品内部网关从现有 `ToolRegistry`
冻结可用目录，Engine 首轮只把工具名称和描述发给模型；模型调用
`load_tool` 后，才在后续模型请求中得到该工具的参数 Schema。

Engine 内置的 Workspace 文件清单、文件读取、沙箱、隔离写入和 diff
继续由 Engine 工具负责。功能已被这些 Workspace 工具覆盖的
`project_manifest` 与 `project_read_file` 不进入 ReAct 模型目录，但旧执行器
仍保留给其他链路。

## 同步检索工具

第一批额外开放以下现有同步工具：

- `search_web`：外部公开 Web 只读检索；
- `search_knowledge`：当前认证用户可见的知识库检索；
- `recommend_literature`：同步生成文献推荐结果，不修改 Project。

这三个名称使用固定 allow-list，并同时校验现有 `ToolDescriptor` 的模型可见性、
PROJECT profile、同步模式、免确认策略、side-effect 类型和 resource scope。
描述符漂移时工具自动退出目录，目录和执行入口使用同一判断。

模型只提供各工具 Schema 中的业务参数。`userId`、`projectId`、Task 和冻结
ProjectVersion 来自任务凭证及服务端认证上下文，既不出现在参数 Schema 中，
也不能由模型覆盖。网关在目录读取及执行前后重新确认当前 ProjectVersion。

## 异步文献任务工具

ReAct 目录还复用现有的 `literature_search_start`、
`literature_search_status`、`literature_search_result` 和
`literature_search_cancel`。创建工具只向模型暴露查询、数量、年份和 BibTeX
选项；幂等 `clientRequestId` 与当前 `projectId` 由网关确定性补入。模型尝试
提交这两个服务端字段时请求失败闭合。

状态、结果和取消只接受任务 ID 及取消原因。现有 task service 按服务端注入的
当前用户重新校验任务所有权。取消只作用于该用户拥有的文献任务，并保留原有
幂等终态语义。

## 论文任务读取工具

`paper_polish_status` 与 `paper_polish_result` 可读取当前认证用户已有论文任务的
状态、结果摘要和产物位置。任务所有权仍由现有 service 使用服务端用户身份校验。
`paper_task_cancel` 暂不进入目录，因为它的现有描述符要求副作用确认，而当前
ReAct 工具网关没有对应的确定性确认边界。

## 非目标

- 不开放 `paper_task_cancel`；
- 不开放旧 Candidate proposal；
- 不按任务类型或模型判断动态生成目录；
- 不改变旧 Agent 链路的 ToolRegistry 注册或策略。
