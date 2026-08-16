# ReAct Agent 前端测试

适用范围：Issue #160，依赖 PR #158 的 ReAct 后端。该页面接入只用于 P1 的 Project 只读与沙箱执行，不提供文件修改、发布或回滚。

## 启动

先按照 `react-agent-local-run.md` 启动产品后端、ReAct Engine 和沙箱 broker，并确保后端启用了 ReAct 与 gateway 开关。然后启动现有前端：

```powershell
Set-Location frontend
pnpm install --frozen-lockfile
pnpm dev
```

浏览器继续使用产品原有登录流程，不需要复制 access token，也不需要把模型或 broker 凭证放进前端。

## 测试步骤

1. 打开 Project 页面，选择一个已经上传的大项目和一个会话。
2. 在页面上方的链路选择中点击 `ReAct 测试`。默认仍是原来的 `正式链路`。
3. 在原有输入框描述任务，例如：“在项目里找到包含 `Sort.java` 的小项目，判断应该怎样运行，并在沙箱里验证结果。”
4. 不要手动选择文件或工具。模型会根据产品动态提供的只读工具寻找目标，再决定是否执行沙箱。
5. 页面任务卡片会显示安全的工具摘要、状态和 Receipt 引用；不会展示文件正文、隐藏推理或 broker 凭证。
6. 如果模型提出必要问题，直接在同一个输入框回复；运行期间可以点击“取消任务”。
7. 刷新页面后，前端使用 Project/session 绑定的本地任务索引重新读取同一个后端任务，并从最后一个 SSE sequence 继续。

## 显示边界

- 任务状态、工具事件、Receipt 引用和最终结论来自后端正式事件；前端不推断成功。
- 本地存储只是恢复索引，不是权威历史。它保存任务身份、用户指令和已裁剪事件，不保存 access token、broker 凭证或文件正文。
- 当前后端没有按 session 查询全部 ReAct 历史的接口，因此页面只恢复该 session 最近一次由本浏览器提交的 ReAct 任务。完整跨设备历史需要单独的后端 Issue。
- 切换 Project、会话或离开页面会中止旧 SSE；旧响应不能更新新页面。

## 验证

```powershell
Set-Location frontend
pnpm exec vitest run src/utils/__tests__/reactPlanTask.test.ts src/views/__tests__/ProjectPreviewPageReactPlan.test.ts src/views/__tests__/ProjectPreviewPageV2Conversation.test.ts
$env:CI = 'true'
pnpm build
```
