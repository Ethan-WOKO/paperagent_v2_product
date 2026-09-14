# Tasks: 知识库原文件下载

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[contracts/http.md](contracts/http.md)
**Tests**: 根 AGENTS.md 要求新增成功与失败自动化测试。
**Status**: 15/15 任务完成；实施、定向验证、converge、commit/push 和 Draft PR 更新完成。真实存储/游客浏览器联调未执行，见 verification.md。

## Phase 1: Setup

- [x] T001 记录用户对规格/方案/分析的实施前确认及 reviewer 清单结果于 specs/002-kb-document-download/verification.md，核对 issue-contract.md 与分支状态；未经确认不进入业务代码。

## Phase 2: Foundational

- [x] T002 新增发布者查询接口 yanban-knowledge/src/main/java/com/yanban/knowledge/service/KnowledgeDocumentPublisherPolicy.java，api 实现于 yanban-api/src/main/java/com/yanban/api/knowledge/KnowledgeDocumentPublisherPolicyAdapter.java，SysUserRepository.java 仅增加未删除 ADMIN 查询；不放开失败路径（FR-009）。
- [x] T003 在 yanban-api/src/test/java/com/yanban/api/knowledge/KnowledgeDocumentPublisherPolicyAdapterTest.java 覆盖 ADMIN/USER/角色撤销/账号删除与空结果，保持 knowledge 到 api 无依赖（FR-004/009）。

## Phase 3: US1 下载自己的原文件

**Independent test**: 本人私有/公开/解析失败文件原字节一致；无原文件与存储故障明确失败。

- [x] T004 [US1] 新增 yanban-knowledge/src/test/java/com/yanban/knowledge/service/KnowledgeDocumentServiceTest.java，覆盖本人下载、SUPERSEDED、DELETED/ARCHIVED、缺失原文件、NoSuchKey/存储异常、流关闭与字节一致（FR-002/005/006，SC-001）。
- [x] T005 [US1] 在 yanban-knowledge/src/main/java/com/yanban/knowledge/service/KnowledgeDocumentService.java 新增受控原文件下载及 KnowledgeDocumentDownload.java 资源载体；先鉴权再存储，保留原 owner-only 方法（FR-002/005/006）。
- [x] T006 [US1] 在 yanban-knowledge/src/main/java/com/yanban/knowledge/web/KnowledgeController.java 增加下载 GET 与安全附件响应；新增 yanban-api/src/test/java/com/yanban/api/knowledge/KnowledgeDocumentDownloadControllerTest.java 覆盖中文/危险文件名、401/404/503、响应头和字节（FR-002/005/006）。
- [x] T007 [US1] 在 frontend/src/api/knowledge.ts 与 frontend/src/views/KnowledgeBasePage.vue 增加鉴权 Blob 下载/加载/错误/防重复和临时 URL 清理；新增 frontend/tests/knowledgeDownload.test.ts 覆盖成功与错误不保存、原文件不可用（FR-005/007）。

## Phase 4: US2 管理员公开资料

**Independent test**: 成员和 DEMO 列表/下载管理员公开文件；管理员本人无重复；旧页面在权限变动后被拒绝。

- [x] T008 [US2] 在 yanban-knowledge/src/main/java/com/yanban/knowledge/domain/KbDocumentRepository.java 与 service/KnowledgeDocumentService.java 增加管理员公开候选列表和去重排序；web/KbDocumentListItemResponse.java 添加权限标志、非本人错误字段清理，KnowledgeController 列表接入（FR-001/003，SC-003）。
- [x] T009 [US2] 在 yanban-knowledge/src/test/java/com/yanban/knowledge/domain/KnowledgeRepositoryTest.java 和 service/KnowledgeDocumentServiceTest.java 覆盖共享 READY/ACTIVE、空管理员集合、排序去重、null 版本默认、角色/公开撤销后拒绝；扩充 api/knowledge/KnowledgeDocumentDownloadControllerTest.java 的成员/DEMO 场景（FR-001/003/004/009，SC-002/003）。
- [x] T010 [US2] 在 frontend/src/views/KnowledgeBasePage.vue 加管理员公开标识、本地文件名筛选与空结果；共享行隐藏 preview/delete，本人保留；必要时只调整 frontend/src/styles/knowledge-workspace.css 的操作布局；frontend/tests/knowledgeDownload.test.ts 覆盖这些行为（FR-007，SC-003）。

## Phase 5: US3 隔离与检索不变

**Independent test**: 他人普通公开文档仍可搜索，但不可列出/下载；自己原预览删除保持。

- [x] T011 [US3] 在 yanban-api/src/test/java/com/yanban/api/agent/KnowledgeControllerIntegrationTest.java 保留普通公开跨用户搜索断言，补充普通公开/私有及管理员私有列表下载拒绝、管理员不绕过他人私有、owner 预览删除和 DEMO_SEED 回归（FR-004/008，SC-002/004）。
- [x] T012 [US3] 在 yanban-knowledge/src/test/java/com/yanban/knowledge/service/KnowledgeDocumentServiceTest.java 验证越权/不存在/无发布者接口在存储访问前拒绝；frontend/tests/knowledgePagePresentation.test.ts 保留上传/预览布局回归（FR-004/006/008）。

## Phase 6: Verification and delivery

- [x] T013 执行 specs/002-kb-document-download/quickstart.md 的 Java、Vitest、build 和 diff 检查，记录精确命令、数量和失败/跳过于 verification.md；有本地环境时执行成员/游客真实下载验收，无环境明确标记未执行（FR-001–009，SC-001–004）。
- [x] T014 使用 speckit-converge 对照 specs/002-kb-document-download/spec.md、plan.md、tasks.md 复核行为和保护边界，遗漏追加到 tasks.md；禁止把剩余验收标为完成。
- [x] T015 在 specs/002-kb-document-download/verification.md 完成回滚/重启/风险与 owned-path 证据，验证后仅暂存本需求文件 commit/push，更新现有 Draft PR #220 关联 #221 与新增范围，不合并、不提交 .runtime。

## Dependencies / Parallel opportunities

T001 → T002 → T003 → US1(T004–007) → US2(T008–010) → US3(T011–012) → T013 → T014 → T015。
不标 [P]：规模小且 service/controller/page/test 多次共享文件，顺序实现更适合最小改动。US1 控制器测试和前端测试可在接口冻结后由不同执行者独立编写；US2/US3 权限矩阵与前端断言也可独立设计，但不得并行改同一文件。

## Implementation strategy

先打通本人下载作为 MVP，再加入管理员共享，最后做跨用户隔离与原检索回归。每阶段可独立验收，但全部 P1 完成才满足本次需求。

## Traceability

| Requirement | Tasks |
| --- | --- |
| FR-001 | T008,T009,T013 |
| FR-002 | T004,T005,T006,T013 |
| FR-003 | T008,T009,T013 |
| FR-004 | T003,T009,T011,T012,T013 |
| FR-005 | T004,T005,T006,T007,T013 |
| FR-006 | T004,T005,T006,T012,T013 |
| FR-007 | T007,T010,T012,T013 |
| FR-008 | T011,T012,T013 |
| FR-009 | T002,T003,T009,T013 |

SC-001 → T004/T006；SC-002 → T009/T011/T012；SC-003 → T008/T009/T010；SC-004 → T011/T012；均在 T013 汇总验证。T001/T014/T015 对应项目流程门禁。
