# Implementation Plan: 知识库原文件下载

**Branch**: codex/issue-185-react-optimization | **Date**: 2026-09-14 | **Spec**: [spec.md](spec.md)
**Issue**: [#221](https://github.com/Ethan-WOKO/paperagent_v2_product/issues/221)

## Summary

原列表新增管理员公开资料、文件名筛选和下载按钮。后端从既有 MinIO 流式返回原文件；文件权限独立于不变的 RAG 权限。历史无原文件明确不可下载。

## Technical Context

Java 17 / Spring Boot MVC/JPA / MinIO；Vue 3 / TypeScript / Naive UI / Axios。测试采用 JUnit、Mockito、JPA/H2、MockMvc、Vitest 和前端 build。复用 kb_document、sys_user 和对象存储，无迁移。仅一个知识库页面、列表加法与一个下载 GET。列表批量查询管理员，不逐文档查角色、不查询 MinIO；后端流式下载避免整文件内存分配，前端沿用 Blob 方式。

## Constitution Check

有效 Issue #221 冻结本次范围，owned paths 见 [issue-contract.md](issue-contract.md)。用户明确要求本地 185 分支/worktree，作为新分支惯例的授权例外。保留 specs/001、.runtime 和已有 Draft PR #220。无反向模块依赖，无 V2、ProjectVersion 或用户数据变化。

Phase 0/Phase 1 技术边界检查通过。用户在完整产物交付后明确回复“批准”，人工实施门禁已满足；reviewer 清单原始标记保留，批准记录见 verification.md，不把自检视为批准。

## Project Structure

文档：specs/002-kb-document-download 下 spec、plan、research、data-model、contracts/http、quickstart、checklists、tasks、issue-contract。
源码：KnowledgeDocumentService、KnowledgeController、KbDocumentRepository、列表 DTO；新增 knowledge 发布者查询接口和 api 实现桥接；SysUserRepository 仅新增管理员查询；frontend 知识库 API/page 及定向测试。确切范围见 issue-contract。

## Design

1. knowledge 定义 KnowledgeDocumentPublisherPolicy 接口，api 用 SysUserRepository 实现，查询当前未删除 ADMIN 的 ID 集合和单人资格。api 已依赖 knowledge；不扩充 core，不按访问者角色判断。接口缺失/失败不得放开共享。
2. 保留 listOwnedDocuments 给内部原调用方，新增页面可见列表入口：owner 列表 UNION 管理员公开 READY/ACTIVE/未删除记录，按 updatedAt desc、id desc 去重排序；空管理员集合不产生非法 IN 查询。
3. DTO 增加 ownedByCurrentUser、administratorPublic、downloadAvailable；不删旧字段、不返回 objectKey；非本人 errorMessage 置空。downloadAvailable 不探测存储，只表示状态/权限/键存在。
4. 下载先检查认证、所有权或管理员公开条件和状态，再访问 MinIO；返回受控资源流并在成功/失败关闭流，不暴露 bucket、objectKey 或公共链接。
5. 新增 GET /api/v1/kb/documents/{id}/download；application/octet-stream、attachment UTF-8 安全文件名、private/no-store、nosniff。文件名去路径/控制字符，空名回退 document-{id}。
6. 无权限/不存在/已删除/ARCHIVED/无 objectKey/NoSuchKey 为 404；存储临时故障 503。流传输开始后故障终止传输，不追加异常内容或宣称能改变已发送状态。
7. 前端使用鉴权 http Blob 下载；成功后创建 object URL 保存并清理；抑制重复点击、加载/错误提示。仅 owner 展示原 preview/delete，DEMO_SEED 限制保持。增加文件名本地筛选，搜索服务不变。
8. deletedAt 非空或 DELETED、ARCHIVED 禁止所有下载；共享额外要求 READY/ACTIVE，null versionStatus 沿用 ACTIVE 默认；owner 原文件不受解析状态影响，SUPERSEDED 可下载。

## Failure / Compatibility

旧 DTO 加法兼容。现有 preview/delete 方法和权限、上传/RAG 全部保持。管理员取消公开、角色失效、账号删除后新下载按最新数据库判断，无权限缓存；已交付字节不可收回。历史无原文件不回填、不拼接 chunks。没有新增扫描/审核基础设施。

## Verification / Rollback / Restart

[quickstart.md](quickstart.md) 列出测试命令与矩阵。覆盖权限、原字节、缺失文件、存储故障、文件名和流关闭。保留普通公开跨用户搜索固定回归；不修改模型/检索算法，不运行全量 RAG 或真实模型 eval，明确记录跳过原因。

回滚本功能提交恢复旧列表和移除下载；数据库/对象保持。应用需重启 API、重建发布前端，Engine/ES/MinIO 无需改动。converge 与验证完成后 commit/push 并更新同分支 Draft PR #220，不重复创建同 head/base PR，不合并。

## Complexity Tracking

用户指定复用 185 分支，以独立 Issue #221 和 SDD 文件界定范围，不重写已关闭 #185 合同；无新增基础设施。
