import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('项目页 V1 / V2 模式切换', () => {
  const source = readFileSync(
    new URL('../src/views/ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('把旧会话和 V2 工作台放在两个明确模式中', () => {
    expect(source).toContain("type ProjectAgentMode = 'v1' | 'v2'");
    expect(source).toContain("route.query.agent === 'v2' ? 'v2' : 'v1'");
    expect(source).toContain("@click=\"setAgentMode('v1')\"");
    expect(source).toContain("@click=\"setAgentMode('v2')\"");
    expect(source).toContain("v-if=\"agentMode === 'v1'\" class=\"project-scroll-shell\"");
    expect(source).toContain("v-if=\"agentMode === 'v1'\" class=\"project-composer\"");
    expect(source).toContain("v-if=\"agentMode === 'v2'\" class=\"v2-conversation\"");
  });

  it('V2 页面使用一个自然语言入口并提供中文过程说明', () => {
    expect(source).toContain('V2 项目助手');
    expect(source).toContain('直接说明你想完成什么');
    expect(source).toContain('执行过程');
    expect(source).toContain('结果：{{ step.detail }}');
    expect(source).toContain('最终结果');
    expect(source).toContain('生成内容位置');
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(source).not.toContain('@click="startProjectAnalysis"');
    expect(source).not.toContain('@click="startProjectCandidate"');
  });

  it('切换模式时保留同一个项目和会话查询参数', () => {
    expect(source).toContain('const query = { ...route.query }');
    expect(source).toContain("query.agent = 'v2'");
    expect(source).toContain('void router.replace({ query })');
  });
});
