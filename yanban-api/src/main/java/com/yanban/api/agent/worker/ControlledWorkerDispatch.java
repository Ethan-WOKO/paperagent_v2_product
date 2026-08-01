package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.core.agent.worker.WorkerServerAuthority;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskAttestor;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ResearchRuntimeScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-only dispatch attached after strategy selection and before Runtime adapter resolution. */
public final class ControlledWorkerDispatch {

    private final WorkerServerAuthority authority;
    private final long trustedUserId;
    private final long trustedProjectId;
    private final Long trustedSessionId;
    private final String parentRunId;
    private final ProjectVersionRef projectVersion;
    private final List<Task> tasks;
    private final Map<ProjectRelativePath, Long> fileSizes;
    private final Map<ProjectRelativePath, FileHash> fileHashes;
    private final int parentMaxSteps;
    private final int parentMaxTokens;
    private final int parentMaxDuplicateToolCalls;
    private final int parentSynthesisMaxTokens;

    ControlledWorkerDispatch(WorkerServerAuthority authority,
                             long trustedUserId,
                             long trustedProjectId,
                             Long trustedSessionId,
                             String parentRunId,
                             ProjectVersionRef projectVersion,
                             List<Task> tasks,
                             Map<ProjectRelativePath, Long> fileSizes,
                             Map<ProjectRelativePath, FileHash> fileHashes,
                             int parentMaxSteps,
                             int parentMaxTokens,
                             int parentMaxDuplicateToolCalls,
                             int parentSynthesisMaxTokens) {
        if (authority == null || trustedUserId < 1 || trustedProjectId < 1
                || parentRunId == null || !parentRunId.equals(authority.parentRunId())
                || projectVersion == null || tasks == null || tasks.size() != 2
                || tasks.stream().anyMatch(java.util.Objects::isNull)
                || fileSizes == null || fileHashes == null || parentMaxSteps < 5 || parentMaxTokens < 1
                || parentMaxDuplicateToolCalls < 0 || parentSynthesisMaxTokens < 1) {
            throw new IllegalArgumentException("controlled Worker dispatch is incomplete");
        }
        if (!authority.projectVersion().equals(projectVersion)) {
            throw new IllegalArgumentException("controlled Worker dispatch version does not match its authority");
        }
        java.util.LinkedHashSet<String> taskIds = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<ProjectRelativePath> assigned = new java.util.LinkedHashSet<>();
        for (Task task : tasks) {
            if (!taskIds.add(task.attestation().packet().workerTaskId())) {
                throw new IllegalArgumentException("controlled Worker dispatch contains duplicate tasks");
            }
            for (ProjectRelativePath path : task.attestation().packet().materialScope()) {
                if (!assigned.add(path)) {
                    throw new IllegalArgumentException("controlled Worker tasks overlap material paths");
                }
            }
        }
        if (!fileSizes.keySet().equals(assigned) || fileSizes.values().stream().anyMatch(size -> size == null || size < 0)) {
            throw new IllegalArgumentException("controlled Worker dispatch sizes must cover every assigned path exactly");
        }
        if (!fileHashes.keySet().equals(assigned) || fileHashes.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("controlled Worker dispatch hashes must cover every assigned path exactly");
        }
        this.authority = authority;
        this.trustedUserId = trustedUserId;
        this.trustedProjectId = trustedProjectId;
        this.trustedSessionId = trustedSessionId;
        this.parentRunId = parentRunId;
        this.projectVersion = projectVersion;
        this.tasks = List.copyOf(tasks);
        this.fileSizes = Map.copyOf(new LinkedHashMap<>(fileSizes));
        this.fileHashes = Map.copyOf(new LinkedHashMap<>(fileHashes));
        this.parentMaxSteps = parentMaxSteps;
        this.parentMaxTokens = parentMaxTokens;
        this.parentMaxDuplicateToolCalls = parentMaxDuplicateToolCalls;
        this.parentSynthesisMaxTokens = parentSynthesisMaxTokens;
    }

    public WorkerServerAuthority authority() { return authority; }
    public long trustedUserId() { return trustedUserId; }
    public long trustedProjectId() { return trustedProjectId; }
    public Long trustedSessionId() { return trustedSessionId; }
    public String parentRunId() { return parentRunId; }
    public ProjectVersionRef projectVersion() { return projectVersion; }
    public List<Task> tasks() { return tasks; }
    public Map<ProjectRelativePath, Long> fileSizes() { return fileSizes; }
    public Map<ProjectRelativePath, FileHash> fileHashes() { return fileHashes; }
    public int parentMaxSteps() { return parentMaxSteps; }
    public int parentMaxTokens() { return parentMaxTokens; }
    public int parentMaxDuplicateToolCalls() { return parentMaxDuplicateToolCalls; }
    public int parentSynthesisMaxTokens() { return parentSynthesisMaxTokens; }

    /** Re-attests the immutable dispatch to the persisted parent Plan identity before execution. */
    public ControlledWorkerDispatch bindToParentPlan(long planId) {
        if (planId < 1) throw new IllegalArgumentException("persisted parent plan id is required");
        String planRunId = "AGENT_PLAN:" + planId;
        if (planRunId.equals(parentRunId)) return this;
        AgentRunIdentity identity = new AgentRunIdentity("AGENT_PLAN", Long.toString(planId), trustedUserId,
                trustedSessionId, trustedProjectId);
        ResearchRuntimeScope runtimeScope = new ResearchRuntimeScope(trustedProjectId, trustedUserId,
                java.util.Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), projectVersion);
        WorkerServerAuthority reboundAuthority = WorkerServerAuthority.serverResolved(identity, runtimeScope,
                authority.parentAllowedReadTools(), authority.parentBudget());
        List<Task> reboundTasks = new java.util.ArrayList<>();
        for (Task task : tasks) {
            WorkerTaskPacket packet = task.attestation().packet();
            String role = packet.workerTaskId().substring(packet.workerTaskId().lastIndexOf(':') + 1);
            WorkerTaskPacket reboundPacket = new WorkerTaskPacket(planRunId + ":" + role, planRunId,
                    packet.projectVersion(), packet.materialAssignments(), packet.objective(),
                    packet.successCriteria(), packet.allowedReadTools(), packet.allowedFindingKeys(),
                    packet.budget(), packet.evidenceRefs());
            reboundTasks.add(new Task(WorkerTaskAttestor.attestServerResolved(reboundAuthority, reboundPacket),
                    task.maxSteps(), task.maxTokens()));
        }
        return new ControlledWorkerDispatch(reboundAuthority, trustedUserId, trustedProjectId, trustedSessionId,
                planRunId, projectVersion, reboundTasks, fileSizes, fileHashes, parentMaxSteps, parentMaxTokens,
                parentMaxDuplicateToolCalls, parentSynthesisMaxTokens);
    }

    public void validateAgainst(AgentRuntimeRequest request) {
        if (request == null || request.projectContext() == null
                || !request.userId().equals(request.projectContext().userId())
                || request.userId().longValue() != trustedUserId
                || request.projectContext().projectId().longValue() != trustedProjectId
                || !java.util.Objects.equals(request.sessionId(), trustedSessionId)
                || !parentRunId.equals(parentRunId(request))
                || request.toolPolicy().maxToolCalls() != authority.parentBudget().maxToolCalls()
                || request.toolPolicy().maxDuplicateToolCalls() != parentMaxDuplicateToolCalls
                || request.maxSteps() != parentMaxSteps
                || request.maxTokens() == null || request.maxTokens() != parentMaxTokens) {
            throw new IllegalArgumentException("controlled Worker dispatch does not match the runtime boundary");
        }
        java.util.Set<String> currentTools = java.util.Set.copyOf(request.toolPolicy().allowedTools());
        if (!currentTools.containsAll(authority.parentAllowedReadTools())
                || tasks.stream().flatMap(task -> task.attestation().packet().allowedReadTools().stream())
                .anyMatch(tool -> !currentTools.contains(tool))) {
            throw new IllegalArgumentException("controlled Worker dispatch exceeds the current parent tool policy");
        }
        int allocatedToolCalls = tasks.stream()
                .mapToInt(task -> task.attestation().packet().budget().maxToolCalls()).sum();
        int allocatedSteps = tasks.stream().mapToInt(Task::maxSteps).sum() + 1;
        int allocatedTokens = tasks.stream().mapToInt(Task::maxTokens).sum() + parentSynthesisMaxTokens;
        if (allocatedToolCalls != request.toolPolicy().maxToolCalls()
                || allocatedSteps > parentMaxSteps
                || allocatedTokens != parentMaxTokens) {
            throw new IllegalArgumentException("controlled Worker dispatch does not conserve the parent budget");
        }
    }

    static String parentRunId(AgentRuntimeRequest request) {
        return request.planId() == null ? "CONTROLLED_WORKER:" + request.traceId()
                : "AGENT_PLAN:" + request.planId();
    }

    public record Task(WorkerTaskAttestation attestation, int maxSteps, int maxTokens) {
        public Task {
            if (attestation == null || maxSteps < 2 || maxTokens < 1) {
                throw new IllegalArgumentException("controlled Worker dispatch task is incomplete");
            }
        }
    }
}
