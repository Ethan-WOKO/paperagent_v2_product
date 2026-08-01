package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentOrchestrationRequirements;
import com.yanban.api.agent.AgentRuntimeMode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentRuntimeResult;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentToolCallingMode;
import com.yanban.api.agent.EvidenceLedger;
import com.yanban.api.agent.LangChain4jToolCallingStrategy;
import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.core.agent.worker.WorkerMaterialAssignment;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reuses the existing model/tool loop without carrying parent conversation history into a Worker. */
@Component
class LangChainControlledWorkerTaskRunner implements ControlledWorkerTaskRunner {

    private final LangChain4jToolCallingStrategy toolCallingStrategy;

    LangChainControlledWorkerTaskRunner(LangChain4jToolCallingStrategy toolCallingStrategy) {
        this.toolCallingStrategy = toolCallingStrategy;
    }

    @Override
    public ControlledWorkerTaskRun run(AgentRuntimeRequest parent, ControlledWorkerDispatch.Task task) {
        WorkerTaskPacket packet = task.attestation().packet();
        AgentRuntimeRequest child = new AgentRuntimeRequest(
                AgentStrategy.SINGLE_STEP_REACT,
                parent.sessionId(),
                List.of(ChatMessage.system(taskInstruction(packet))),
                parent.userId(),
                packet.objective(),
                parent.provider(),
                parent.model(),
                parent.temperature(),
                task.maxTokens(),
                task.maxSteps(),
                true,
                null,
                parent.apiKey(),
                parent.apiUrl(),
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(packet.allowedReadTools(), packet.budget().maxToolCalls(),
                        Math.min(parent.toolPolicy().maxDuplicateToolCalls(), packet.budget().maxToolCalls()),
                        "controlled_worker_task_intersection"),
                packet.budget().maxToolCalls(),
                Math.min(parent.toolPolicy().maxDuplicateToolCalls(), packet.budget().maxToolCalls()),
                parent.traceId() + ":" + packet.workerTaskId(),
                null,
                null,
                null,
                parent.projectContext(),
                EvidenceLedger.empty(),
                AgentOrchestrationRequirements.empty(),
                false,
                null);
        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task.attestation())) {
            AgentRuntimeResult result = toolCallingStrategy.run(child);
            ControlledWorkerExecutionScope.Snapshot snapshot = scope.snapshot();
            return new ControlledWorkerTaskRun(result, snapshot.executions(), snapshot.rejection());
        }
    }

    private String taskInstruction(WorkerTaskPacket packet) {
        StringBuilder instruction = new StringBuilder(
                "You are a server-bounded read-only research Worker. Use only the assigned tools and paths. "
                        + "Do not request other files, create plans, write candidates, use commands, use network access, "
                        + "or claim parent completion. Produce a concise evidence-bound observation for the parent.\n"
                        + "Worker task id: ").append(packet.workerTaskId()).append("\nAssigned materials:\n");
        for (WorkerMaterialAssignment assignment : packet.materialAssignments()) {
            instruction.append("- ").append(assignment.materialType()).append(": ")
                    .append(assignment.relativePath().value()).append("\n");
        }
        instruction.append("Allowed tools: ").append(String.join(", ", packet.allowedReadTools())).append("\n")
                .append("Every tool call must explicitly include only the assigned relativePaths.");
        return instruction.toString();
    }
}
