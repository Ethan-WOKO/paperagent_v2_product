# Data Model

日期：2026-09-14。不新增持久字段或表。

| 现有数据 | 用途 |
| --- | --- |
| KbDocument.id/userId | 文档与所有权；请求身份来自认证上下文 |
| isPublic | 与发布者 ADMIN 共同授权共享文件，RAG 含义不变 |
| status/versionStatus/deletedAt | 共享 READY/ACTIVE/未删除；本人解析状态不限；DELETED/ARCHIVED 禁止下载 |
| objectKey | 仅后端定位原文件，缺失标记不可下载，不向客户端返回 |
| filename/updatedAt | 安全下载名称与稳定排序，id 打破排序平局 |
| SysUser.role/deletedAt | 当前有效 ADMIN，角色失效或删除撤销共享 |

DTO 增加 ownedByCurrentUser、administratorPublic、downloadAvailable。后者不探测远端存储，只表示权限/状态/键可用。非本人 errorMessage 置空。null versionStatus 沿用 ACTIVE 默认，不改变生命周期。
