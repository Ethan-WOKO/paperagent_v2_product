import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { consumeChatSseChunk } from '../src/utils/chatSse';

describe('chat SSE transport', () => {
  it('parses complete frames and retains a partial frame', () => {
    const parsed = consumeChatSseChunk(
      'event: ack\r\ndata: {"type":"ack","clientRequestId":"r1"}\r\n\r\n'
      + 'event: chunk\ndata: {"type":"chunk","content":"hel',
    );

    expect(parsed.events).toHaveLength(1);
    expect(parsed.events[0]).toMatchObject({ type: 'ack', clientRequestId: 'r1' });
    expect(parsed.remainder).toContain('event: chunk');
  });

  it('uses authenticated HTTP SSE and contains no browser WebSocket path', () => {
    const api = readFileSync(new URL('../src/api/agent.ts', import.meta.url), 'utf8');
    const page = readFileSync(new URL('../src/views/ChatPage.vue', import.meta.url), 'utf8');

    expect(api).toContain("Accept: 'text/event-stream'");
    expect(api).toContain('Authorization = `Bearer ${token}`');
    expect(page).toContain('streamMessage as streamAgentMessage');
    expect(page).not.toContain('new WebSocket(');
    expect(page).not.toContain('/api/v1/ws/chat');
  });
});
