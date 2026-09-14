# Phase 0 Research

日期：2026-09-14。依据本地代码与 speckit-plan 要求的独立研究代理只读结论。

## 原文件

- Decision: 下载既有 MinIO objectKey 对应字节。
- Rationale: 页面使用 KnowledgeUploadService.mergeChunks，已保存原文件；ingestSimple/ingestText 不保留原文件，包括部分历史/DEMO_SEED。
- Alternatives: 拼接 chunks 不能恢复原文件；扩展所有上传路径和回填超范围，不采用。

## 角色与游客

- Decision: 查询访问时未删除 ADMIN 发布者；knowledge 定义接口、api 实现；保留认证下载。
- Rationale: SysUser 在 api，knowledge 不可反向依赖；api 已依赖 knowledge，可实现其接口。游客通过 AuthController 的 demo-login 获取 DEMO JWT，不需匿名放行。
- Alternatives: core 端口也是可行方案，但本功能局部接口更少改动。按上传时角色冻结需新数据，不采用；不能相信前端标志或访问者 ADMIN。

## 页面与验证

- Decision: 原列表加本地文件名筛选、共享标识与下载；他人行不展示 owner-only preview/delete。
- Rationale: 保持最小范围。KnowledgeControllerIntegrationTest 已含普通公开跨用户搜索回归；KnowledgeRepositoryTest 和 FileProcessingServiceTest 提供 JPA/MinIO mock 模式。
- Alternatives: 新资料中心、全文索引、预签名公共链接、扩大共享预览均不必要。

技术未知已解决；没有读线上文件或用户数据。真实存储/浏览器流程属于实施验收，本研究不是运行证明。
