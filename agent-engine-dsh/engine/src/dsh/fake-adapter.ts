import { appendFileSync } from 'node:fs';
import { LlmAdapter } from '@deepseek-ai/dsh-llm';
import type { GenerateOptions, LlmModelInfo, LlmProviderInfo, LlmResolvedModelInfo, StreamChunk } from '@deepseek-ai/dsh-llm';

/** Deterministic fake adapter for formal-path tests: scripts tool calls and
 * final text without a provider. Every stream() call is counted in the engine
 * data dir so budget/recovery tests can assert exact provider-call counts. */
export class FakeAdapter extends LlmAdapter {
  private readonly callsLogPath: string;

  constructor(callsLogPath: string) {
    super();
    this.callsLogPath = callsLogPath;
  }

  providerInfo(provider: string): LlmProviderInfo {
    return { id: provider, name: 'Fake Test Adapter' } as LlmProviderInfo;
  }

  providerRetryPolicy(): undefined {
    return undefined;
  }

  async listModels(): Promise<readonly LlmModelInfo[]> {
    return [{ id: 'fake-model', name: 'Fake Model' }] as LlmModelInfo[];
  }

  async resolveModel(provider: string, model: string): Promise<LlmResolvedModelInfo> {
    return { id: model, name: model, provider, model } as unknown as LlmResolvedModelInfo;
  }

  async *stream(options: GenerateOptions): AsyncIterable<StreamChunk> {
    appendFileSync(this.callsLogPath, JSON.stringify({ at: new Date().toISOString() }) + '\n', 'utf8');
    const mode = process.env.FAKE_MODE ?? 'normal';
    const sawToolResult = options.messages.some((message) => (message.content ?? []).some((block) => (block as { type: string }).type === 'tool-result'));
    const finalize = mode === 'normal' || mode === 'ask' ? sawToolResult : false;
    if (finalize) {
      const delayMs = Number(process.env.FAKE_DELAY_MS ?? 0);
      if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
      yield { type: 'block-start', index: 0, blockType: 'text' };
      yield { type: 'text-delta', index: 0, text: 'Conclusion: the sandbox run succeeded with exit code 0. No files were modified.' };
      yield { type: 'block-end', index: 0, block: { type: 'text', text: 'Conclusion: the sandbox run succeeded with exit code 0. No files were modified.' } };
      yield { type: 'finish', reason: { kind: 'stop' } };
      return;
    }
    const toolName = mode === 'ask' ? 'ask_user' : 'sandbox_execute';
    const args = mode === 'ask'
      ? JSON.stringify({ question: 'Should I continue?' })
      : JSON.stringify({ argv: ['javac', 'src/main/java/Sort.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: 120000 });
    yield { type: 'block-start', index: 0, blockType: 'tool-call' };
    yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: toolName, argumentsDelta: args };
    yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: toolName, arguments: args } as never };
    yield { type: 'finish', reason: { kind: 'tool-calls' } };
  }
}
