import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('ProjectPreviewPage V2-only 中文任务界面', () => {
  const source = readFileSync(
    new URL('../ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('默认只渲染 V2 页面并删除旧 V1 对话 UI', () => {
    expect(source).toContain('<section class="v2-conversation">');
    expect(source).not.toContain("@click=\"setAgentMode('v1')\"");
    expect(source).not.toContain('class="project-scroll-shell"');
    expect(source).not.toContain('class="project-composer"');
  });

  it('只有一个中文自然语言输入，不再显示重复的任务说明', () => {
    expect(source).not.toContain('class="v2-conversation__header"');
    expect(source).not.toContain('class="project-agent-mode"');
    expect(source).toContain('class="v2-conversation__composer"');
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(source).not.toContain('aria-label="选择 V2 任务类型"');
    expect(source).not.toContain('@click="startProjectAnalysis"');
    expect(source).not.toContain('@click="startProjectCandidate"');
  });

  it('每个问题展示一个结果、折叠过程和 Candidate 交付状态', () => {
    expect(source).toContain('v-for="task in v2TurnHistory"');
    expect(source).not.toContain('v2-task-card__role');
    expect(source).toContain("task.status === 'SUCCEEDED'");
    expect(source).toContain("task.status === 'FAILED'");
    expect(source).toContain("task.status === 'WAITING_CONFIRMATION'");
    expect(source).toContain('class="v2-conversation__process"');
    expect(source).toContain(':open="task.status === \'PLANNING\' || task.status === \'RUNNING\'"');
    expect(source).toContain("task.status === 'WAITING_CONFIRMATION' ? '等待确认' : '已处理'");
    expect(source).toContain('结果：{{ step.detail }}');
    expect(source).toContain('生成内容位置');
    expect(source).toContain('原项目尚未修改');
    expect(source).toContain('已自动保存，已创建项目版本');
    expect(source).toContain("v2TaskApplied(task)");
    expect(source).toContain("candidateConfirmationLabel(task.confirmationValidation)");
    expect(source).toContain('查看修改');
    expect(source).toContain('最终沙箱运行');
    expect(source).toContain('项目版本状态');
    expect(source).toContain('v-if="!selectedCandidateApplied" class="project-candidate-sandbox__controls"');
    expect(source).toContain('v-if="task.status === \'SUCCEEDED\' && task.finalText"');
  });

  it('服务端任务列表负责刷新恢复，localStorage 只保存进行中的同一请求', () => {
    expect(source).toContain('listV2NaturalLanguageTurns(sessionId, 50)');
    expect(source).toContain('V2_NATURAL_LANGUAGE_STORAGE_KEY');
    expect(source).toContain('storeV2NaturalLanguageRequest(projectId, sessionId, clientRequestId, question)');
    expect(source).toContain('recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId)');
    expect(source).toContain('onUnmounted(() =>');
    expect(source).toContain('stopV2NaturalLanguagePolling();');
    expect(source).toContain('resume: async () =>');
    expect(source).toContain('refreshProjectAfterV2AutoApply(clientRequestId, epoch)');
    expect(source).toContain('refreshProjectAfterV2AutoApply(stored.clientRequestId, epoch)');
  });
});
