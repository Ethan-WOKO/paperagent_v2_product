import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url));
const source = readFileSync(pagePath, 'utf8');

describe('Project unified input contract', () => {
  it('has one V2 composer and submits through the persistent natural-language turn route', () => {
    expect(source.match(/v-model:value="v2TurnInput"/g)).toHaveLength(1);
    expect(source).not.toContain('v-model:value="chatInput"');
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(source).toContain('@keydown="handleV2TurnKeydown"');
    expect(source).toContain("event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey)");
    expect(source).toContain('event.preventDefault()');
    expect(source).toContain('void sendV2NaturalLanguageTurn()');
    expect(source).toContain('startV2NaturalLanguageTurn(sessionId, request, controller.signal)');
    expect(source).not.toContain('v-model:value="planInput"');
    expect(source).not.toContain('@click="createPlan"');
  });

  it('presents persistent Steps as collapsed execution details rather than a second submission mode', () => {
    expect(source).toContain('class="v2-conversation__process"');
    expect(source).not.toContain('class="v2-conversation__process" open');
    expect(source).toContain('v-for="step in task.steps"');
    expect(source).toContain(':content="task.finalText"');
    expect(source).not.toContain('Create a governed Project plan');
    expect(source).not.toContain('Create plan</NButton>');
  });
});
