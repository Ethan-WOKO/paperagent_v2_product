# 会话读取缓存与页面加载

日期：2026-09-19。状态：第一批实现，关联 #230。#228 暂停，不继续退役。

## 当前覆盖

- 工作区最近一页消息：保留原来的 all/chat 视图语义与 1–100 条查询上限；缓存键区分用户、会话、视图、条数和数据版本。带 beforeId 的历史翻页直接查询数据库。
- 项目对话：缓存已结束任务最近 200 条事件的读取结果。任务列表、指令、检查点、当前状态、权限继续从数据库读取。事件键含用户、会话、任务、lastSequence 和 checkpointRevision，运行中任务不缓存。
- 项目当前对话与候选修改、旧历史并行加载，辅助请求不会阻塞当前对话请求发起；保留项目/会话切换隔离。
- 未改引擎执行、数据库结构、消息接口或前端视图选择。暂未缓存项目/会话列表、模型目录，不把本批描述为所有页面都已完成优化。

## 一致性

数据库为权威来源。消息新增、更新、删除经 AgentMessage JPA 生命周期发布变更事件，在事务提交后失效缓存；同一事务同一会话只失效一次，回滚不失效。旧的“从空缓存追加一条就当作完整历史”逻辑已移除。

首次数据库读取之前取得 Redis 随机版本，旧事务的读取结果只写回它原来的版本。变更提交后切换版本，因此旧请求晚到不会污染当前版本。缓存命中也必须查数据库确认会话归属；缓存不承担授权或任务状态判定。

快照 TTL 120 秒、每项 UTF-8 上限 1 MiB，版本键 TTL 1 小时；旧版本快照自动过期，不使用 KEYS/FLUSHDB。超限、损坏、缺失均回源。Redis 读写异常不使业务请求失败，当前实例绕过缓存 125 秒；失效写入仍会尝试，以便恢复后切换版本。

这是可丢弃的读取缓存，不是强一致跨系统事务：数据库提交与 Redis 失效之间仍有短暂窗口；Redis 失效失败或实例在提交后崩溃时，其他实例可能读取尚未过期的旧消息快照，最长受 120 秒快照 TTL 限制。权限仍实时校验。若后续要求跨实例消息修改强一致，需要数据库版本或可靠失效 outbox，不应仅延长 TTL。

## 配置与回退

- YANBAN_CONVERSATION_CACHE_ENABLED=true，设为 false 并重启 Java 即绕过本批缓存。
- YANBAN_REDIS_CONNECT_TIMEOUT=300ms。
- YANBAN_REDIS_COMMAND_TIMEOUT=300ms。
- Redis 地址、端口、密码沿用原有 Spring Redis 配置；不同部署环境必须隔离 Redis 实例/逻辑库。
- 键前缀 paperagent:conversation:v1:；旧 chat:user:...:recent 不再读取，随原 TTL 自行过期。
- 数据仍保存在数据库，无数据库迁移和历史数据转换。发布需要更新 Java 与前端，不需要替换 TypeScript 引擎。

## Python 并行开发边界

Python 服务先仅在 agent-engine-python/ 内新增代码、测试和说明，使用独立端口及 Redis 前缀 paperagent:python:dev:。不要改现有 Java/前端/引擎/部署文件，不直接写共享数据库表，不清空共享 Redis，不启动接管现有任务的进程。

接入时明确消息、事件、任务状态和缓存失效协议。通过现有 Java JPA 路径写消息会触发生命周期失效；Python/SQL/批量 JPQL 绕过 JPA 的写入不会触发，必须另行接入提交后的失效机制，不能直接复用当前写表方式。

共享 checkout 不得切分支、reset/clean/stash/pull/merge 或提交别人的文件；只显式暂存自己负责的路径。Python 接入作为后续工作，当前 TS 仍是项目运行链路。

## 验证与性能界限

定向测试验证命中时跳过消息正文查询但仍校验归属；视图/条数/用户隔离；旧版本回填；冷缓存变更；历史分页；失败回源；载荷上限；事务新增/修改/删除/回滚；项目终态快照复用与运行中/新序列绕过；前端并行加载。

真实 Redis 验证只连接单独创建的临时容器。使用 -Dconversation.test.redis.port=16390，并对 Spring 集成测试同时指定 -Dspring.data.redis.port=16390 -Dspring.data.redis.password=。默认不运行真实 Redis 测试，需显式传入端口。

未声称已测得真实用户页面加速比例。验收应使用同一会话分别记录首次和重复访问的消息接口、项目 tasks 接口耗时及页面可见时间。首次项目事件缓存未命中仍使用既有事件查询，长任务事件的数据库侧限量查询留待后续优化。Redis 不解决浏览器大包加载、长列表渲染、网络时延或模型首字等待。

## 2026-09-19 性能对照实测

使用独立 MySQL 8.4（13391）和 Redis 7（16391）容器，未使用业务数据库或共享 Redis。最终测试仅装配 5 个相关实体/Repository，使用真实 JPA 与 Redis，执行实际查询服务和 JSON 序列化；AgentService 用 CALLS_REAL_METHODS 测试外壳注入真实会话/消息 Repository 和缓存，未替换被测读取方法。属于合成数据的服务层测试，不是浏览器/HTTP 压测，也没有真实模型调用。

每个场景先预热，再交替执行缓存关闭/命中各 60 次，以及缓存未命中 20 次；共 420 次测量逐次比较响应 JSON 字节完全一致。每次读取使用独立事务，统计 Hibernate prepareStatement 次数。无并发负载，MySQL 数据页同样经过预热。消息表使用现有生产 session/created_at 和 user/created_at 索引。工作区每个会话生成 500 条消息，读取最新 50 条；内容为 ASCII 合成正文。项目生成 6 个终态任务，每任务 500 条事件，仍返回最近 200 条/任务。

最终运行结果（单位 ms；P95 表示 95% 的样本不超过该耗时）：

| 场景 | 关闭：中位 / P95 | 命中：中位 / P95 | 未命中：中位 / P95 | SQL 关闭→命中 |
| --- | --- | --- | --- | --- |
| 工作区 50 条，每条约 500 字符 | 12.18 / 17.53 | 12.74 / 17.24 | 13.73 / 15.38 | 2→1 |
| 工作区 50 条，每条约 4000 字符 | 10.54 / 12.55 | 10.26 / 12.61 | 14.95 / 16.78 | 2→1 |
| 项目 6 任务，共 3000 条事件 | 41.81 / 53.23 | 15.90 / 20.15 | 50.77 / 57.16 | 5→4 |

结论：项目长历史命中时，中位耗时降低约 62%，P95 降低约 62%；工作区两个规模的中位耗时基本持平，小消息甚至略慢。缓存确实减少了数据库查询，但不能据此宣称工作区页面已经明显提速。未命中需要额外回填，三个场景首次读取都更慢。前面复测同样得到“项目长历史约 60% 改善、工作区无稳定中位提速”的方向；最终表格来自收窄实体范围并隔离 benchmark profile 后的完整运行。

复现命令（仅创建临时实例，端口不能指向真实服务）：

```powershell
docker run --detach --rm --name paperagent-230-perf-redis --publish 127.0.0.1:16391:6379 redis:7-alpine redis-server --save '' --appendonly no
docker run --detach --rm --name paperagent-230-perf-mysql --publish 127.0.0.1:13391:3306 --env MYSQL_ROOT_PASSWORD=isolated_benchmark_only --env MYSQL_DATABASE=conversation_perf mysql:8.4
# 等临时 MySQL 就绪后执行。密码仅为这里新建的临时测试实例使用。
mvn -pl yanban-api -am '-Dtest=ConversationCachePerformanceTest' '-Dconversation.perf.enabled=true' '-Dsurefire.failIfNoSpecifiedTests=false' test
docker stop paperagent-230-perf-mysql paperagent-230-perf-redis
```

最终 1 个 benchmark 测试通过，0 失败/错误/跳过。测试默认关闭，显式 opt-in 才连接固定临时端口；专用 Spring profile 防止干扰普通测试配置。没有设置“Redis 必须更快”的耗时断言，避免把机器抖动当功能失败；断言的是响应完全一致和命中时 SQL 减少。性能结果从 CACHE_PERF 行输出。

尚未覆盖：真实账户浏览器首屏、页面渲染、HTTP 网络耗时、多人并发和生产容量。当前不能把“每次打开页面卡一下”的问题归因于数据库，也不能把本表解释为整个页面快 62%。本轮仅新增测试及文档，没有新增或修改业务功能。
