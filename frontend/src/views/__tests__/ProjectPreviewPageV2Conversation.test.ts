import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('ProjectPreviewPage V2 中文会话界面', () => {
  const source = readFileSync(
    new URL('../ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('保留 V1/V2 切换，并让两套页面互斥显示', () => {
    expect(source).toContain("@click=\"setAgentMode('v1')\"");
    expect(source).toContain("@click=\"setAgentMode('v2')\"");
    expect(source).toContain("v-if=\"agentMode === 'v1'\" class=\"project-scroll-shell\"");
    expect(source).toContain("v-if=\"agentMode === 'v1'\" class=\"project-composer\"");
    expect(source).toContain("v-if=\"agentMode === 'v2'\" class=\"v2-conversation\"");
  });

  it('V2 只有一个中文自然语言输入，不再显示两种任务表单', () => {
    expect(source).toContain('V2 项目助手');
    expect(source).toContain('直接说明你想完成什么');
    expect(source).toContain('class="v2-conversation__composer"');
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(source).not.toContain('aria-label="选择 V2 任务类型"');
    expect(source).not.toContain('@click="startProjectAnalysis"');
    expect(source).not.toContain('@click="startProjectCandidate"');
  });

  it('展示真实步骤、三种终态、输出位置和 Candidate 入口', () => {
    expect(source).toContain('执行过程');
    expect(source).toContain('最终结果');
    expect(source).toContain('v2NaturalLanguageStepStatusLabel(step.status)');
    expect(source).toContain('结果：{{ step.detail }}');
    expect(source).toContain("v2TurnOutcome.status === 'SUCCEEDED'");
    expect(source).toContain("v2TurnOutcome.status === 'FAILED'");
    expect(source).toContain("v2TurnOutcome.status === 'WAITING_CONFIRMATION'");
    expect(source).toContain('生成内容位置');
    expect(source).toContain('原项目尚未修改');
    expect(source).toContain('打开修改与验证');
    expect(source).toContain('@click="openV2CandidateReview"');
    expect(source).toContain("v2TurnOutcome.route === 'DIRECT'");
    expect(source).toContain('此问题无需执行项目步骤，已直接回答。');
  });

  it('切换范围和卸载会中止轮询，刷新可恢复同一请求', () => {
    expect(source).toContain('V2_NATURAL_LANGUAGE_STORAGE_KEY');
    expect(source).toContain('storeV2NaturalLanguageRequest(projectId, sessionId, clientRequestId, question)');
    expect(source).toContain('recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId)');
    expect(source).toContain('resetV2NaturalLanguageView();');
    expect(source).toContain('onUnmounted(() =>');
    expect(source).toContain('stopV2NaturalLanguagePolling();');
    expect(source).toContain('resume: async () =>');
  });
});
