import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url));
const source = readFileSync(pagePath, 'utf8');

describe('ReActPlan progress presentation', () => {
  it('merges progress messages and tool calls into one chronological process timeline', () => {
    expect(source).toContain('activity: reactPlanActivityEvents(record.events)');
    expect(source).toContain('v-if="item.activity.length"');
    expect(source).toContain('v-for="activity in item.activity"');
    expect(source).toContain("activity.type === 'message'");
    expect(source).toContain(':content="activity.content"');
    expect(source).toContain('{{ item.activity.length }} 条记录');
    expect(source).not.toContain('reactplan-stage-messages');
  });

  it('defaults execution details to collapsed and preserves the user-selected state', () => {
    expect(source).toContain(':open="reactPlanProcessIsOpen(item.record)"');
    expect(source).toContain('@toggle="rememberReactPlanProcessOpen(item.record.taskId, $event)"');
    expect(source).toContain('return remembered ?? false;');
    expect(source).not.toContain("record.events.some((event) => event.type === 'tool' || event.type === 'message')");
  });
});
