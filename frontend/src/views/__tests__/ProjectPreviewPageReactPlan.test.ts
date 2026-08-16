import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('ProjectPreviewPage ReAct 接入', () => {
  const source = readFileSync(
    new URL('../ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('在现有 Project 对话中提供轻量链路切换且正式链路保持默认', () => {
    expect(source).toContain("const projectAgentRoute = ref<ProjectAgentRoute>('v2')");
    expect(source).toContain("@click=\"setProjectAgentRoute('v2')\"");
    expect(source).toContain("@click=\"setProjectAgentRoute('react')\"");
    expect(source.match(/class="v2-conversation__composer"/g)).toHaveLength(1);
    expect(source).toContain('v-if="projectAgentRoute === \'v2\'"');
    expect(source).toContain('v-else class="v2-conversation__tasks"');
  });

  it('提交自然语言任务并消费带断点的认证 SSE，而不是固定工具流程', () => {
    expect(source).toContain('startReactPlanTask(');
    expect(source).toContain('streamReactPlanEvents(');
    expect(source).toContain('appendReactPlanEvent(current.events, event, current.taskId)');
    expect(source).toContain('connectReactPlanTask(record, epoch)');
    expect(source).toContain('reactPlanToolEvents(reactPlanRecord.value?.events ?? [])');
    expect(source).not.toContain('reactPlanFixedTool');
  });

  it('展示安全工具摘要、正式 Receipt、追问、取消和最终 delivery', () => {
    expect(source).toContain('tool.outputSummary || tool.inputSummary');
    expect(source).toContain('tool.receiptRef');
    expect(source).toContain('reactPlanDeliveryEvent.conclusion');
    expect(source).toContain('reactPlanQuestion.text');
    expect(source).toContain('answerCurrentReactPlanQuestion');
    expect(source).toContain('@click="cancelCurrentReactPlanTask"');
    expect(source).not.toContain('tool.fileContent');
    expect(source).not.toContain('tool.rawOutput');
  });

  it('Project、session 和卸载时中止旧流并按身份恢复', () => {
    expect(source).toContain('record.projectId === activeProjectId.value');
    expect(source).toContain('record.sessionId === activeSessionId.value');
    expect(source).toContain('record.taskId === reactPlanRecord.value?.taskId');
    expect(source).toContain('resetReactPlanView();');
    expect(source).toContain('invalidateReactPlanStream();');
    expect(source).toContain('loadReactPlanRecord(activeProjectId.value, sessionId, epoch)');
  });
});
