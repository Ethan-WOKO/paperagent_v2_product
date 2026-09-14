# Validation Guide

日期：2026-09-14。以下为复验命令；实际执行结果、数量和限制见 [verification.md](verification.md)。

## Prerequisites

Java 17、Maven、项目现有依赖和 pnpm。单元测试 mock MinIO；JPA/API 使用 H2。人工使用可丢弃测试账号和文件，不提交账号、上传资料或日志。

## Focused checks

仓库根目录（新增类完成后）：

```powershell
mvn -q -pl yanban-api -am '-Dtest=KnowledgeDocumentServiceTest,KnowledgeRepositoryTest,KnowledgeDocumentPublisherPolicyAdapterTest,KnowledgeDocumentDownloadControllerTest,KnowledgeControllerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

如既有全上下文测试因基础设施失败，精确记录并补充真实 Controller/Service/SecurityFilterChain 的 focused slice，不删除或弱化普通公开搜索回归。API 编译同时验证桥接依赖。

前端目录：

```powershell
pnpm exec vitest run tests/knowledgeDownload.test.ts tests/knowledgePagePresentation.test.ts
$env:CI='true'
pnpm build
```

仓库根目录：

```powershell
git diff --check
```

## Behavior matrix

1. 本人私有/公开/解析失败原文件字节一致；本人 SUPERSEDED 可下载。
2. 成员、DEMO 可列出下载 ADMIN 公开 READY/ACTIVE；管理员本人不重复。
3. 他人普通公开/私有、ADMIN 私有、已删除/ARCHIVED 拒绝；管理员也不可绕过他人私有。拒绝前不访问 MinIO。
4. 管理员撤销/删除、取消公开、共享状态失效，旧页面直取拒绝。
5. 匿名 401；无键/NoSuchKey 404；存储故障 503；中文/危险文件名安全，流关闭且不泄露。
6. 文件名筛选/空结果，下载加载/失败/防重复，错误 Blob 不保存，object URL 清理；共享无 preview/delete，本人操作保留。
7. 固定普通公开跨用户 /search 仍命中，上传/本人预览删除/DEMO_SEED 保护保持。

## Manual acceptance and evidence

管理员页面上传勾选公开；普通成员与游客体验进入知识库，按文件名筛选、下载并对比原字节；本人上传私有文件后下载。无真实环境则标记未执行。

实施后 verification.md 记录实际命令、测试数量、失败/跳过原因、构建和人工结果。完整 RAG/真实 LLM 不作为必需，因为其逻辑不修改；固定权限回归必须执行。

## Rollback / restart

回滚功能提交恢复旧列表、移除下载，数据库/文件保留。重启 API、重建前端，Engine/ES/MinIO 无需配置变化。
