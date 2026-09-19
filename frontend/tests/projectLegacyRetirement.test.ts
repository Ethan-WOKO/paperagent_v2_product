import { readFileSync } from 'node:fs';
import { describe, it, expect } from 'vitest';
const page = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
describe('Project ReAct retirement boundary', () => {
  it('has no legacy start or automatic recovery path in the Project page', () => {
    expect(page).not.toContain('startV2NaturalLanguageTurn');
    expect(page).not.toContain('recoverV2NaturalLanguageTurn');
    expect(page).not.toContain('async function sendV2NaturalLanguageTurn');
  });
  it('retains current ReAct execution and owner-qualified historical loading', () => {
    expect(page).toContain('await startReactPlanTask(sessionId');
    expect(page).toContain('listV2NaturalLanguageTurns(sessionId');
    expect(page).toContain('cancelCurrentReactPlanTask');
  });
});
