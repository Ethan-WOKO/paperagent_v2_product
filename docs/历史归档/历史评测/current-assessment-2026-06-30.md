# 当前版本评估报告（2026-06-30）

## 结论

当前项目的工程自动化基线通过，已经具备“内部试用 / Limited Beta”的基础，但不建议直接作为付费客户的正式生产版本上线。

推荐 v1 对外定位：

| 能力 | 建议状态 |
|---|---|
| 普通科研学习聊天 | v1 核心能力 |
| 私有知识库问答 / RAG | v1 核心能力，但需完成真实环境人工评测 |
| 知识库上传、列表、检索调试 | v1 核心能力 |
| Plan-and-Execute | Beta，不作为客户 SLA 承诺 |
| Web 搜索 / 文献搜索 | Beta，可作为增强能力 |
| 论文工作流 | Preview，不作为 v1 主卖点 |
| MCP | Preview，需单独开关和白名单 |

综合判断：当前成熟度约为 `内部试用通过，客户 Limited Beta 待人工验收`。

## 本次已完成评测资产

| 文件 | 用途 |
|---|---|
| `docs/evaluation/README.md` | 评测方法、评分规则、上线门槛 |
| `docs/evaluation/v1-eval-suite.md` | P0/P1/P2 测试集 |
| `docs/evaluation/launch-checklist.md` | 上线 checklist |
| `docs/evaluation/current-assessment-2026-06-30.md` | 当前评估报告 |

## 自动化验证结果

| 项目 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 后端全量测试 | `mvn test` | 通过 | Reactor 全部 SUCCESS，总耗时 04:22 |
| 前端构建 | `pnpm build` | 通过 | Vite 构建成功，耗时 38.73s |

后端测试模块结果：

| 模块 | 结果 | 测试数 |
|---|---:|---:|
| yanban-core | 通过 | 18 |
| yanban-knowledge | 通过 | 7 |
| yanban-paper | 通过 | 33 |
| yanban-mcp | 通过 | 3 |
| yanban-skills | 通过 | 1 |
| yanban-api | 通过 | 51 |
| yanban-cli | 通过 | 0 |
| 合计 | 通过 | 113 |

前端构建结果：

| 指标 | 结果 |
|---|---|
| TypeScript 编译 | 通过 |
| Vite build | 通过 |
| 产物 JS | `819.67 kB` |
| Warning | chunk 超过 500 kB，建议后续 code splitting |

## 当前能力分级

| 能力域 | 当前状态 | 判断 |
|---|---|---|
| 鉴权与会话 | 绿色 | JWT、会话、消息、权限测试已覆盖 |
| 知识库基础链路 | 黄色偏绿 | Repository、上传、检索、权限测试通过；真实 MinIO/Kafka/ES/DashScope 端到端仍需人工验收 |
| RAG 问答质量 | 黄色 | 工程链路存在，但真实模型回答质量还未按评测集打分 |
| 模型 Provider | 黄色 | DeepSeek/GLM 单测覆盖；GLM 真实网络稳定性此前出现过 PrematureClose，需要观察 |
| Harness 工具循环 | 黄色偏绿 | 已加入工具预算与最终总结兜底；仍需真实模型验证是否收敛 |
| Plan Agent | 黄色偏红 | 已有 DAG、状态、恢复、降级、并行；主 agent 决策门仍不够明确，建议 beta |
| Web 搜索子 agent | 黄色 | 有搜索、top-k 综合、降级；搜索质量与重复搜索控制需人工评测 |
| 前端 | 黄色偏绿 | 构建通过，核心页面存在；移动端和长任务体验需人工验收 |
| 论文工作流 | 黄色偏红 | 测试通过但 README 已说明 skeleton-first，不建议 v1 主推 |
| MCP | 黄色偏红 | 测试中多次出现 filesystem MCP 注册 warning；生产需默认关闭或严格白名单 |
| 运维上线 | 黄色偏红 | 部署文档仍是草案，备份恢复未实测 |

## 本次发现的非阻塞风险

| 风险 | 等级 | 说明 | 建议 |
|---|---|---|---|
| 真实模型质量未系统评测 | 高 | 自动化测试主要使用 mock，不代表客户真实回答质量 | 执行 `v1-eval-suite.md` 的 P0/P1 人工评测 |
| Plan Agent 决策不稳定 | 高 | 目前仍可能依赖模型自发决定是否继续工具调用 | 下一阶段加入主 agent 结构化决策门 |
| GLM 网络稳定性 | 中 | 之前真实调用出现 PrematureClose；本次单测覆盖了重试但不代表公网稳定 | 生产记录 provider 错误率并保留 DeepSeek fallback |
| 前端 bundle 偏大 | 中 | Vite 提示 JS chunk 819.67 kB | 后续按路由拆包 |
| MCP 注册 warning | 中 | 测试启动时 filesystem MCP 多次跳过注册 | v1 默认关闭 MCP，作为高级功能手动开启 |
| 生产部署未闭环 | 高 | `docs/DEPLOYMENT.md` 仍是草案 | 补 Dockerfile/Nginx/备份恢复实测 |
| 人工 E2E 未跑 | 高 | 还没实际上传样本文档并执行 P0/P1 问答打分 | 上线前必须补 |

## Go / No-Go 判定

| 场景 | 判定 | 原因 |
|---|---|---|
| 本地开发继续迭代 | Go | 自动化基线通过 |
| 团队内部试用 | Go | 核心链路可试，风险可接受 |
| 小范围客户试点 | Conditional Go | 必须先跑完 P0 人工用例，且 RAG/聊天 P1 平均 >= 4.0 |
| 正式付费生产上线 | No-Go | 缺少真实模型质量报告、备份恢复实测、生产部署闭环 |

## 推荐下一步

1. 按 `v1-eval-suite.md` 执行 P0 全部用例，至少覆盖 DeepSeek。
2. 上传 `docs/kb-samples/*.md`，执行 P1 RAG 问答并记录 0-5 分。
3. 用 GLM 重跑 P0-CHAT、P0-RAG、P0-TOOLS，重点观察网络失败和工具循环。
4. 把 Plan 模式从 v1 核心卖点降级为 beta，并在前端显式标注。
5. 增加主 agent 决策门：子 agent 返回后先判断 `synthesize/search_more/fail/ask_user`，再决定是否开放工具。
6. 补生产部署闭环：Dockerfile、Nginx、备份恢复、日志脱敏、监控指标。

## 当前阶段一句话

项目已经从“能跑的原型”进入“可内部试用的 beta”，但距离“可以放心交给客户长期使用”的差距主要不在功能数量，而在真实评测、产品边界、部署运维和失败可控性。
