import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('项目页 V2-only 入口', () => {
  const source = readFileSync(
    new URL('../src/views/ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('移除 V1/V2 切换、旧会话区域和旧输入框', () => {
    expect(source).not.toContain("type ProjectAgentMode = 'v1' | 'v2'");
    expect(source).not.toContain("@click=\"setAgentMode('v1')\"");
    expect(source).not.toContain("@click=\"setAgentMode('v2')\"");
    expect(source).not.toContain('class="project-scroll-shell"');
    expect(source).not.toContain('class="project-composer"');
    expect(source).toContain('<section class="v2-conversation">');
  });

  it('保留项目共用检查入口并只提供 V2 自然语言输入', () => {
    expect(source).toContain('文件预览');
    expect(source).toContain('证据 <span>{{ evidence.length }}</span>');
    expect(source).toContain('修改与验证');
    expect(source).toContain('项目版本');
    expect(source).toContain('class="v2-conversation__composer"');
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(source).not.toContain('@click="startProjectAnalysis"');
    expect(source).not.toContain('@click="startProjectCandidate"');
  });
});
