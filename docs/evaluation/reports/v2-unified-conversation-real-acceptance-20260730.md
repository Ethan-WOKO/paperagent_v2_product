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
| 后端 Spring Boot | **启动失败，阻断后续真实链路验收** |
| 前端 | 未启动；后端失败后按停止条件终止 |

## 阻断缺陷

Spring 无法创建 `V2ModelReflectionProvider`。该类被标记为 `@Component`，
但现在有两个构造方法，Spring 不知道应使用哪一个，于是错误地尝试寻找无参构造方法，
最终以 `NoSuchMethodException: V2ModelReflectionProvider.<init>()` 终止启动。

这个问题由为 request-scoped provider 增加第二个构造方法后产生。代码可以编译，
但现有定向单元测试没有启动完整 Spring 应用上下文，因此没有发现该问题。

## 最小修复建议（尚未实施）

1. 仅在 `V2ModelReflectionProvider` 的二参数构造方法上明确标记 Spring 注入，
   让应用级默认实例继续只接收 `ModelProvider` 和 `ObjectMapper`。
2. 保留五参数构造方法，继续供运行时创建携带本次请求 TaskFrame、Plan 和 Revision
   标识的反思 Provider，不改变现有执行设计。
3. 新增一个只覆盖 V2 adaptive 组件装配的 Spring 上下文测试，防止再次出现
   “能编译、单元测试通过、应用却无法启动”的问题。
4. 修复后只运行该装配测试、相关 adaptive 定向测试和一次后端启动检查，
   不扩大到全仓测试。

## 冻结场景执行结果

| 场景 | 结果 | 原因 |
| --- | --- | --- |
| V1/V2 切换及中文单输入框 | 未执行 | 后端启动阻断 |
| DIRECT 请求且不额外 GET | 未执行 | 后端启动阻断 |
| 项目读取进入持久化 Plan | 未执行 | 后端启动阻断 |
| 自然语言生成 Candidate | 未执行 | 后端启动阻断 |
| 确认 Candidate 创建一个新版本 | 未执行 | 后端启动阻断 |
| 沙箱不可用时禁止宿主机回退 | 未执行 | 后端启动阻断 |

## 执行命令与清理

- `mvn -q -o -pl yanban-api -am install -DskipTests`
  - 结果：成功。
  - 说明：仅构建，不运行测试。
- `mvn -pl yanban-api spring-boot:run -Dspring-boot.run.profiles=dev`
  - 结果：失败，退出码 `1`。
  - 直接原因：`V2ModelReflectionProvider` 无法由 Spring 实例化。
- 发现阻断后未启动前端，未发起真实用户请求，也未修改任何测试项目。
- E2B Broker 和本次启动的 Docker 基础设施已停止。

## 结论

本轮不能判定统一 V2 项目会话通过真实链路验收。当前唯一已确认的产品缺陷是
后端启动装配错误；必须先获得用户批准并完成上述最小修复，才能继续六个冻结场景。
