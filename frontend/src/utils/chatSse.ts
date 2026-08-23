export interface ChatStreamEvent {
  type: 'ack' | 'process' | 'chunk' | 'done' | 'error' | 'debug';
  content: string | null;
  sessionId: number | null;
  error: string | null;
  finishReason: string | null;
  navigationUrl: string | null;
  clientRequestId: string | null;
  debug: unknown;
  assistantContent: string | null;
}

export function consumeChatSseChunk(input: string) {
  const normalized = input.replace(/\r\n/g, '\n');
  const frames = normalized.split('\n\n');
  const remainder = frames.pop() ?? '';
  const events: ChatStreamEvent[] = [];
  for (const frame of frames) {
    const data = frame.split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (data) events.push(JSON.parse(data) as ChatStreamEvent);
  }
  return { events, remainder };
}
