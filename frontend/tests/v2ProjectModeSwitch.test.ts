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
    expect(source).toContain("v-if=\"agentMode === 'v2'\" class=\"v2-workbench__hero\"");
  });

  it('V2 页面只使用显式 V2 任务入口并提供中文过程说明', () => {
    expect(source).toContain('读取并分析');
    expect(source).toContain('生成候选修改');
    expect(source).toContain('执行过程');
    expect(source).toContain('@click="startProjectAnalysis"');
    expect(source).toContain('@click="startProjectCandidate"');
    expect(source).toContain('以下状态来自当前 V2 请求，不是旧 Agent 会话');
    expect(source).toContain('查看修改、运行验证并确认');
  });

  it('切换模式时保留同一个项目和会话查询参数', () => {
    expect(source).toContain('const query = { ...route.query }');
    expect(source).toContain("query.agent = 'v2'");
    expect(source).toContain('void router.replace({ query })');
  });
});
