import { appendFileSync } from 'node:fs';
import { LlmAdapter } from '@deepseek-ai/dsh-llm';
import type { GenerateOptions, LlmModelInfo, LlmProviderInfo, LlmResolvedModelInfo, StreamChunk } from '@deepseek-ai/dsh-llm';

/** Deterministic fake adapter for formal-path tests: scripts tool calls and
 * final text without a provider. Every stream() call is counted in the engine
 * data dir so budget/recovery tests can assert exact provider-call counts, and
 * each call logs the user-side transcript so tests can prove what text the
 * loop actually sent to the model (instruction on fresh runs, delivered
 * answer body after waiting_user recovery). */
export class FakeAdapter extends LlmAdapter {
  private readonly callsLogPath: string;
  private callCount = 0;

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

  private userTexts(options: GenerateOptions): string[] {
    const texts: string[] = [];
    for (const message of options.messages as { role?: string; content?: { type?: string; text?: string }[] }[]) {
      if (message.role !== 'user') continue;
      const text = (message.content ?? [])
        .filter((block) => block.type === 'text')
        .map((block) => block.text ?? '')
        .join('');
      if (text.trim().length > 0) texts.push(text);
    }
    return texts;
  }

  async *stream(options: GenerateOptions): AsyncIterable<StreamChunk> {
    this.callCount++;
    const mode = process.env.FAKE_MODE ?? 'normal';
    const sandboxArgs = () => JSON.stringify({ argv: ['javac', 'src/main/java/Sort.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: Number(process.env.FAKE_TIMEOUT_MS ?? 120000) });
    appendFileSync(
      this.callsLogPath,
      JSON.stringify({ at: new Date().toISOString(), call: this.callCount, userTexts: this.userTexts(options).map((t) => t.slice(0, 500)) }) + '\n',
      'utf8',
    );
    const sawToolResult = options.messages.some((message) => (message.content ?? []).some((block) => (block as { type: string }).type === 'tool-result'));

    const finalizeText = 'Conclusion: the sandbox run succeeded with exit code 0. No files were modified.';
    async function* finalize(): AsyncIterable<StreamChunk> {
      const delayMs = Number(process.env.FAKE_DELAY_MS ?? 0);
      if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
      yield { type: 'block-start', index: 0, blockType: 'text' };
      yield { type: 'text-delta', index: 0, text: finalizeText };
      yield { type: 'block-end', index: 0, block: { type: 'text', text: finalizeText } };
      yield { type: 'finish', reason: { kind: 'stop' } };
    }

    // recover-finalize: after a restart the model concludes directly WITHOUT
    // re-running any tool, proving the ledger re-adopts completed receipts.
    if (mode === 'recover-finalize') {
      yield* finalize();
      return;
    }

    // ask mode: first call asks the user. A call whose transcript already
    // carries the runner-injected answer ("The user answered the pending
    // question…") finalizes immediately — that is the waiting_user recovery
    // path, where the ask_user tool never runs again.
    if (mode === 'ask') {
      const sawInjectedAnswer = this.userTexts(options).some((t) => t.includes('The user answered the pending question'));
      if (this.callCount > 1 || sawInjectedAnswer) {
        yield* finalize();
        return;
      }
      yield { type: 'block-start', index: 0, blockType: 'tool-call' };
      yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'ask_user', argumentsDelta: JSON.stringify({ question: 'Should I continue?' }) };
      yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'ask_user', arguments: JSON.stringify({ question: 'Should I continue?' }) } as never };
      yield { type: 'finish', reason: { kind: 'tool-calls' } };
      return;
    }

    if (mode === 'normal' && sawToolResult) {
      yield* await finalize();
      return;
    }
    if (mode === 'budget') {
      yield { type: 'block-start', index: 0, blockType: 'tool-call' };
      yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'sandbox_execute', argumentsDelta: sandboxArgs() };
      yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'sandbox_execute', arguments: sandboxArgs() } as never };
      yield { type: 'finish', reason: { kind: 'tool-calls' } };
      return;
    }
    // two-attempts: two DISTINCT sandbox executions (different argv → different
    // digest → different clientRequestId), then finalize. Used to test that the
    // terminal classification follows the LAST attempt. FAKE_ATTEMPT2_DELAY_MS
    // (default 0) inserts a pause before the second attempt so crash tests can
    // land between the first receipt and the second dispatch.
    if (mode === 'two-attempts') {
      if (this.callCount === 1) {
        yield { type: 'block-start', index: 0, blockType: 'tool-call' };
        yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'sandbox_execute', argumentsDelta: JSON.stringify({ argv: ['javac', 'src/main/java/Sort.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: 1000 }) };
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'sandbox_execute', arguments: JSON.stringify({ argv: ['javac', 'src/main/java/Sort.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: 1000 }) } as never };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
        return;
      }
      if (this.callCount === 2) {
        const delayMs = Number(process.env.FAKE_ATTEMPT2_DELAY_MS ?? 0);
        if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
        yield { type: 'block-start', index: 0, blockType: 'tool-call' };
        yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'sandbox_execute', argumentsDelta: JSON.stringify({ argv: ['javac', 'src/main/java/Other.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: 1000 }) };
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'sandbox_execute', arguments: JSON.stringify({ argv: ['javac', 'src/main/java/Other.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }], timeoutMillis: 1000 }) } as never };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
        return;
      }
      yield* finalize();
      return;
    }
    // dup-inputs: first call submits a sandbox request with DUPLICATE input
    // paths; the engine must reject it before any dispatch. Subsequent calls
    // finalize so the task reaches a terminal state for assertions.
    if (mode === 'dup-inputs') {
      if (sawToolResult) {
        yield* finalize();
        return;
      }
      const dupArgs = JSON.stringify({
        argv: ['javac', 'src/main/java/Sort.java'],
        inputs: [
          { path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) },
          { path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) },
        ],
        timeoutMillis: 120000,
      });
      yield { type: 'block-start', index: 0, blockType: 'tool-call' };
      yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'sandbox_execute', argumentsDelta: dupArgs };
      yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'sandbox_execute', arguments: dupArgs } as never };
      yield { type: 'finish', reason: { kind: 'tool-calls' } };
      return;
    }
    // normal mode, first call: run the sandbox once.
    yield { type: 'block-start', index: 0, blockType: 'tool-call' };
    yield { type: 'tool-call-delta', index: 0, id: 'fakecall' as never, name: 'sandbox_execute', argumentsDelta: sandboxArgs() };
    yield { type: 'block-end', index: 0, block: { type: 'tool-call', id: 'fakecall', name: 'sandbox_execute', arguments: sandboxArgs() } as never };
    yield { type: 'finish', reason: { kind: 'tool-calls' } };
  }
}
