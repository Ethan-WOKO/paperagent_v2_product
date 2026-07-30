# V2 统一项目会话真实链路验收（2026-07-30）

## 验收范围

- 基线提交：`dc8cd3f198c6bb8fbf4e42f3b0af8d9a3879175c`
- Issue：`#101`
- 仅验证统一 V2 项目会话的六个冻结场景，不扩大回归范围。
- 不读取或记录 `.env`、密钥和用户文件内容。

## 环境准备结果

| 检查 | 结果 |
| --- | --- |
| 后端模块离线安装构建 | 通过 |
| MySQL、Redis、Elasticsearch、Kafka、MinIO | 启动后均为健康状态 |
| E2B Broker | 已启动并监听 `127.0.0.1:8091` |
| 后端 Spring Boot | 首次失败；修复后启动成功 |
| 前端 | 启动成功；Codex 浏览器安全策略拒绝接管本地页面 |

## 阻断缺陷

Spring 无法创建 `V2ModelReflectionProvider`。该类被标记为 `@Component`，
但现在有两个构造方法，Spring 不知道应使用哪一个，于是错误地尝试寻找无参构造方法，
最终以 `NoSuchMethodException: V2ModelReflectionProvider.<init>()` 终止启动。

这个问题由为 request-scoped provider 增加第二个构造方法后产生。代码可以编译，
但现有定向单元测试没有启动完整 Spring 应用上下文，因此没有发现该问题。

## 已批准并完成的修复

用户确认不保留实际产品链路不使用的默认反思 Provider。修复提交
`801eda31334f545f3fc674761929e16a10566997` 已推送到 `main`：

1. 删除 `V2ModelReflectionProvider` 的 Spring `@Component` 和二参数构造方法。
2. 删除 `V2AdaptiveExecutionService` 中 `modelProvider == null` 的默认回退路径。
3. `Command` 现在强制携带请求级 `ModelProvider`。
4. 五参数 Provider 继续携带本次请求的 TaskFrame、Plan 和 Revision 标识。
5. 相关 adaptive 测试改为显式提供请求级 Provider。

仓库已经存在 `YanbanApiApplicationTests.contextLoads`，它能直接发现本次 Spring
装配错误，因此没有重复新增另一份同类测试。

## 第二个阻断缺陷

使用全新项目会话 `203`，连续两次发送明确的“只读取项目，不修改文件，不运行代码”
请求，均返回 HTTP `502`。持久化失败事实均为 `PLANNER_REJECTED`，没有创建 Plan。

当前失败记录只保存统一错误码，没有保存模型原始输出的安全摘要、具体校验字段或解析
阶段。因此现有事实不能判断模型究竟返回了错误工具别名、错误 JSON，还是不符合约束的
Plan 字段。这使真实问题无法继续定位，也无法给出有证据的行为修复。

另一次在有历史对话的旧测试会话 `200` 上，请求成功创建持久化 Plan，但模型额外加入
`project_candidate` 和 `sandbox_execute`，导致已知的
`SANDBOX_EXECUTION_UNAVAILABLE`。该结果不作为纯项目读取通过证据。

### 已完成修复

提交 `3423813` 已推送到 `main`：

1. 将 Planner 失败分成“模型调用失败、JSON 解析失败、字段校验失败”。
2. 失败事实保存安全的失败字段和模型输出 SHA-256 前 12 位；不保存模型原文或 Key。
3. 真实复现得到 `PLANNER_PROJECT_DIRECT_b8db6e5db445`：模型错误地将项目读取
   判断成 DIRECT。
4. 仅向 Project 会话的 Planner 增加明确上下文，要求返回
   `PERSISTENT_PLAN_EXECUTE`；未增加权限规则，未放宽服务端既有校验。
5. 修复后同一请求成功创建持久化 Plan。

## 第三个阻断缺陷

修复 Planner 后，同一个全新项目会话的只读请求创建了包含
`project_search` 和 `project_read` 的两步 Plan，但执行立即以
`ADAPTIVE_EXECUTION_FAILED` 结束；两个步骤仍为 `PENDING`。

持久化事实显示 Plan bootstrap 和 Plan lease 已创建，但 execution start、workspace
context、step activation 和 effect intent 均未创建。因此失败发生在执行启动阶段，
尚未进入 `project_search → project.search` 或 `project_read → project.read` 工具映射。

`V2AdaptiveExecutionService` 当前将该阶段的所有运行时异常统一折叠为
`ADAPTIVE_EXECUTION_FAILED`，且不记录安全失败阶段。静态证据不足以确定是 execution
start 恢复、租约、事件材料还是数据库适配错误，不能猜测修改。

### 下一步最小建议（尚未实施）

1. 仅为 adaptive 执行的 `start / context / cycle / reflection` 四个边界记录安全阶段码。
2. 不保存模型原文、项目文件或 Key，也不改变 Runtime 状态机。
3. 复现一次相同项目读取，根据精确阶段做最小修复。
4. 只运行对应 adaptive 定向测试和一次真实项目读取，不扩大测试范围。

## 冻结场景执行结果

| 场景 | 结果 | 原因 |
| --- | --- | --- |
| V1/V2 切换及中文单输入框 | 部分执行 | 前后端均启动；Codex 浏览器策略阻止自动接管本地页面，需人工确认 UI |
| DIRECT 请求且不额外 GET | 后端通过 | 全新工作区会话 `204` 返回 `DIRECT` 且包含回答；前端零 GET 未在本轮重复自动化 |
| 项目读取进入持久化 Plan | **部分通过后失败** | Planner 已创建正确的持久化 Plan；执行启动以 `ADAPTIVE_EXECUTION_FAILED` 结束 |
| 自然语言生成 Candidate | 未执行 | Planner 阻断后按停止条件终止 |
| 确认 Candidate 创建一个新版本 | 未执行 | 上一场景未生成 Candidate |
| 沙箱不可用时禁止宿主机回退 | 部分通过 | 旧测试会话返回 `SANDBOX_EXECUTION_UNAVAILABLE`，未观察到宿主机回退 |

## 执行命令与清理

- `mvn -q -o -pl yanban-api -am install -DskipTests`
  - 结果：成功。
  - 说明：仅构建，不运行测试。
- `mvn -pl yanban-api spring-boot:run -Dspring-boot.run.profiles=dev`
  - 修复前：失败，`V2ModelReflectionProvider` 无法由 Spring 实例化。
  - 修复后：成功，`/actuator/health` 返回 UP。
- `mvn -q -o -pl yanban-api -Dtest=V2AdaptiveExecutionServiceTest,YanbanApiApplicationTests test`
  - `V2AdaptiveExecutionServiceTest`：4 项，0 failures，0 errors，0 skipped。
  - `YanbanApiApplicationTests`：1 项，0 failures，0 errors，0 skipped。
- `mvn -q -o -pl yanban-api -Dtest=V2TurnPlannerTest,V2NaturalLanguageTurnServiceTest test`
  - `V2TurnPlannerTest`：13 项，0 failures，0 errors，0 skipped。
  - `V2NaturalLanguageTurnServiceTest`：9 项，0 failures，0 errors，0 skipped。
- 真实 HTTP：
  - 工作区 DIRECT：1 次成功。
  - 诊断增强前，全新项目会话只读请求：2 次失败，均为 `PLANNER_REJECTED`。
  - 诊断增强后：精确确认为 `PLANNER_PROJECT_DIRECT`。
  - Project Planner 提示修复后：成功创建两步持久化 Plan，随后执行启动失败。
  - 旧项目会话只读请求：创建 Plan 后因包含 sandbox 能力而失败。
- 使用的会话快照与设置页当前默认值一致：提供商 `deepseek`，模型
  `deepseek-v4-pro`；未读取、输出或记录 Key。
- 创建了两个验收专用会话；没有修改项目文件，也没有创建新项目版本。

## 结论

本轮不能判定统一 V2 项目会话通过真实链路验收。当前唯一已确认的产品缺陷是
adaptive 执行启动异常缺少可诊断阶段事实，项目读取尚未真正执行。反思 Provider 装配
错误、Planner 安全诊断和 Project 持久化路由均已修复并推送。下一步应先增加最小
adaptive 阶段诊断，再依据一次真实复现做最小执行启动修复。
