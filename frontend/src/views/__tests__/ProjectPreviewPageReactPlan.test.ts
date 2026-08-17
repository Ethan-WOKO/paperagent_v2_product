import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('ProjectPreviewPage ReAct 接入', () => {
  const source = readFileSync(
    new URL('../ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('隐藏链路选择并始终默认使用 ReAct', () => {
    expect(source).toContain("aria-label=\"ReAct project task\"");
    expect(source).toContain('v-model:value="reactPlanInput"');
    expect(source).not.toContain("@click=\"setProjectAgentRoute('v2')\"");
    expect(source).not.toContain("@click=\"setProjectAgentRoute('react')\"");
    expect(source).not.toContain('class="project-agent-mode"');
    expect(source).not.toContain('ReAct <small>测试</small>');
    expect(source.match(/class="v2-conversation__composer"/g)).toHaveLength(1);
    expect(source).not.toContain('projectAgentRoute');
    expect(source).toContain(':aria-busy="reactPlanBusy"');
  });

  it('提交自然语言任务并消费带断点的认证 SSE，而不是固定工具流程', () => {
    expect(source).toContain('startReactPlanTask(');
    expect(source).toContain('streamReactPlanEvents(');
    expect(source).toContain('appendReactPlanEvent(current.events, event, current.taskId)');
    expect(source).toContain('connectReactPlanTask(record, epoch)');
    expect(source).toContain('reactPlanToolEvents(record.events)');
    expect(source).not.toContain('reactPlanFixedTool');
  });

  it('在同一会话中按时间线保留并恢复多轮 ReAct 任务', () => {
    expect(source).toContain('const reactPlanRecords = ref<ReactPlanTaskRecord[]>([])');
    expect(source).toContain('v-for="item in reactPlanTimeline"');
    expect(source).toContain('upsertReactPlanRecord(reactPlanRecords.value, record)');
    expect(source).toContain('parseReactPlanHistory(raw, projectId, sessionId)');
    expect(source).toContain('serializeReactPlanHistory(records)');
  });

  it('展示安全工具摘要、正式 Receipt、追问、取消和最终 delivery', () => {
    expect(source).toContain('tool.outputSummary || tool.inputSummary');
    expect(source).toContain('reactPlanToolLabel(tool)');
    expect(source).toContain('tool.receiptRef');
    expect(source).toContain('item.delivery.conclusion');
    expect(source).toContain('item.question.text');
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
