import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

describe('document-only Candidate validation', () => {
  const source = readFileSync(
    new URL('../src/views/ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('uses a local document-integrity profile and explains that E2B is not invoked', () => {
    expect(source).toContain("value: 'DOCUMENT_INTEGRITY'");
    expect(source).toContain('文档完整性检查（不启动 E2B）');
    expect(source).toContain('文档不会作为代码执行');
    expect(source).toContain('不会把文档放进 E2B 执行');
    expect(source).toContain("documentOnlyProject ? '确认检查' : '确认并运行'");
  });

  it('offers Maven validation only when the root pom exists', () => {
    expect(source).toContain("file.path === 'pom.xml'");
    expect(source).toContain("{ label: '下载 Maven 依赖后离线测试', value: 'MAVEN_TEST' }");
    expect(source).toContain("{ label: '下载 Maven 依赖后离线验证', value: 'MAVEN_VERIFY' }");
    expect(source).toContain('只在工作区仅包含依赖清单时临时联网下载');
    expect(source).toContain('完整代码上传前会恢复并确认断网');
  });
});
