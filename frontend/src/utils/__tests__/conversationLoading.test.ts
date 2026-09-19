import { describe, expect, it, vi } from 'vitest';
import { loadConversationParts } from '../conversationLoading';

describe('conversation loading', () => {
  it('renders primary history while auxiliary history is still pending', async () => {
    let finish!: () => void;
    const slow = new Promise<void>((resolve) => { finish = resolve; });
    const render = vi.fn();
    const auxiliary = vi.fn(() => slow);
    const done = loadConversationParts(async () => { render(); }, [auxiliary]);
    await Promise.resolve();
    expect(render).toHaveBeenCalledOnce();
    expect(auxiliary).toHaveBeenCalledOnce();
    finish();
    await done;
  });

  it('still loads primary history if an auxiliary loader throws synchronously', async () => {
    const render = vi.fn();
    await expect(loadConversationParts(async () => { render(); }, [() => { throw new Error('history'); }]))
      .rejects.toThrow('history');
    expect(render).toHaveBeenCalledOnce();
  });

  it('waits for all work and reports primary failure without abandoning auxiliary work', async () => {
    const auxiliary = vi.fn(async () => {});
    await expect(loadConversationParts(async () => { throw new Error('primary'); }, [auxiliary]))
      .rejects.toThrow('primary');
    expect(auxiliary).toHaveBeenCalledOnce();
  });
});
