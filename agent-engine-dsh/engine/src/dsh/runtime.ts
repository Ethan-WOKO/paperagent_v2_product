import type { Context } from '@deepseek-ai/cordis';
import type { AgentRegistry, AgentHandle, CreateAgentOptions } from '@deepseek-ai/dsh-agent';
import type { ToolRuntime } from '@deepseek-ai/dsh-tools';
import type { SessionEvent } from '@deepseek-ai/dsh-session';
import type { SystemPrompt } from '@deepseek-ai/dsh-system-prompt';
import { boot } from '@deepseek-ai/dsh-app-boot';

/** Handle to the booted DSH tree, captured once at engine startup. */
export interface DshRuntime {
  ctx: Context;
  agents: AgentRegistry;
  tools: ToolRuntime;
  systemPrompt: SystemPrompt;
  createAgent(options: CreateAgentOptions): Promise<AgentHandle>;
  onSessionEvent(listener: (sessionId: string, event: SessionEvent) => void): () => void;
  onAgentRequest(listener: (sessionId: string, turn: number, step: number, next: () => unknown) => unknown): () => void;
  onAgentStatus(listener: (sessionId: string, status: string) => void): () => void;
}

export class EngineDshRuntime implements DshRuntime {
  readonly ctx: Context;

  constructor(ctx: Context) {
    this.ctx = ctx;
  }

  get agents(): AgentRegistry {
    return this.ctx.agents;
  }

  get tools(): ToolRuntime {
    return this.ctx.tools;
  }

  get systemPrompt(): SystemPrompt {
    return this.ctx.systemPrompt;
  }

  async createAgent(options: CreateAgentOptions): Promise<AgentHandle> {
    return this.ctx.agents.create(options);
  }

  onSessionEvent(listener: (sessionId: string, event: SessionEvent) => void): () => void {
    const disposer = this.ctx.on('session/event', ((session: { id: unknown }, event: SessionEvent) => {
      listener(String(session.id), event);
    }) as never);
    return () => {
      if (typeof disposer === 'function') disposer();
    };
  }

  onAgentRequest(listener: (sessionId: string, turn: number, step: number, next: () => unknown) => unknown): () => void {
    const disposer = this.ctx.on('agent/request', ((payload: { agent: { id: unknown }; turn: number; step: number }, next: () => unknown) =>
      listener(String(payload.agent.id), payload.turn, payload.step, next)) as never);
    return () => {
      if (typeof disposer === 'function') disposer();
    };
  }

  onAgentStatus(listener: (sessionId: string, status: string) => void): () => void {
    const disposer = this.ctx.on('agent/status', ((payload: { agent: { id: unknown }; status: string }) =>
      listener(String(payload.agent.id), payload.status)) as never);
    return () => {
      if (typeof disposer === 'function') disposer();
    };
  }
}

/** Boot the minimal DSH composition defined in engine/cordis.yml. */
export async function bootDsh(configPath: string): Promise<DshRuntime> {
  const ctx = await boot('agent-engine-dsh', configPath);
  return new EngineDshRuntime(ctx);
}
