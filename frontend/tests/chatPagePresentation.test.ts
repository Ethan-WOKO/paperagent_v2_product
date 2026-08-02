import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const chatPath = fileURLToPath(new URL('../src/views/ChatPage.vue', import.meta.url));
const projectPath = fileURLToPath(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url));
const stylePath = fileURLToPath(new URL('../src/styles/chat-workspace.css', import.meta.url));
const chat = readFileSync(chatPath, 'utf8');
const project = readFileSync(projectPath, 'utf8');
const styles = readFileSync(stylePath, 'utf8');

describe('ordinary workspace chat presentation contract', () => {
  it('keeps the execution process inline between the user request and final answer', () => {
    expect(chat).toContain('class="chat-page research-chat-page research-chat-page--redesign"');
    expect(chat.indexOf("localId: 'user-' + Date.now()")).toBeLessThan(chat.indexOf('localId: processId'));
    expect(chat.indexOf('localId: processId')).toBeLessThan(chat.indexOf('localId: assistantId'));
    expect(chat).toContain('collapseCurrentProcessMessage();');
    expect(chat).toContain('message.processOpen = false;');
    expect(chat).toContain('buildPlanProcessContent');
    expect(chat).toContain('setAssistantContent(buildPlanAssistantContent(finalPlan))');
  });

  it('preserves ordinary chat, legacy plan, literature, and attachment contracts', () => {
    expect(chat).toContain('sendMessage as sendAgentMessage');
    expect(chat).toContain('createPlan');
    expect(chat).toContain('startV2LiteratureTurn');
    expect(chat).toContain('mergeKbUpload');
    expect(chat).toContain('uploadChunk');
    expect(chat).not.toContain('createProjectSession');
    expect(chat).not.toContain('startV2NaturalLanguageTurn');
  });

  it('keeps the Project V2 conversation on its own endpoints', () => {
    expect(project).toContain('createProjectSession');
    expect(project).toContain('startV2NaturalLanguageTurn');
    expect(project).not.toContain('sendMessage as sendAgentMessage');
    expect(project).not.toContain('createPlan');
  });

  it('retains optional workspace controls without turning them into a separate process panel', () => {
    expect(chat).toContain('setChatSidebarCollapsed');
    expect(chat).toContain('chatFileInputRef');
    expect(chat).toContain('literatureFormOpen');
    expect(chat).toContain('showProcessMessages');
    expect(chat).not.toContain('agent-sidebar');
  });

  it('uses the shared light and dark tokens with explicit tablet and mobile layouts', () => {
    expect(styles).toContain('background: var(--pa-canvas);');
    expect(styles).toContain('background: var(--pa-surface)');
    expect(styles).toContain('@media (max-width: 980px)');
    expect(styles).toContain('@media (max-width: 760px)');
    expect(styles).toContain('.message-row--process');
    expect(styles).toContain('.process-message-card');
    expect(styles).toContain('.n-button.chat-send-button:last-child .n-button__content');
    expect(styles).toContain('place-items: center !important;');
  });
});
