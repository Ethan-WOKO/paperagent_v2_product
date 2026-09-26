import { Annotation, END, START, StateGraph } from "@langchain/langgraph";

export type ReactDecision = "continue" | "stop";

/** Java checkpoints and call IDs remain authoritative; this graph only schedules steps. */
export async function runReactGraph(steps: {
  tools: () => Promise<ReactDecision>;
  model: () => Promise<ReactDecision>;
}): Promise<void> {
  const state = Annotation.Root({ decision: Annotation<ReactDecision>() });
  const graph = new StateGraph(state)
    .addNode("tools", async () => ({ decision: await steps.tools() }))
    .addNode("model", async () => ({ decision: await steps.model() }))
    .addEdge(START, "tools")
    .addConditionalEdges("tools", (s) => s.decision === "stop" ? END : "model")
    .addConditionalEdges("model", (s) => s.decision === "stop" ? END : "tools")
    .compile();
  // The existing 20-model-call budget is the primary bound, including after restart.
  await graph.invoke({ decision: "continue" }, { recursionLimit: 64 });
}
