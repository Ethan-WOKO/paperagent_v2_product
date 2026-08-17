package com.yanban.api.agent.engine;

import com.yanban.api.agent.engine.AgentEngineGatewayDtos.FileEntry;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.FileList;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.FileRead;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.SandboxInput;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnProjectVersionSourceFactory;
import com.yanban.api.project.ProjectStorageProperties;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspaceException;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineWorkspaceGateway {
    private static final int CONTRACT_MAX_FILES = 4096;
    private final AgentTurnProductContextResolver contexts;
    private final AuthenticatedAgentTurnProjectVersionSourceFactory sources;
    private final ProjectStorageProperties storage;
    private final EngineGatewayProperties properties;
    private final Path root;
    private final ConcurrentMap<String, BoundWorkspace> active = new ConcurrentHashMap<>();

    AgentEngineWorkspaceGateway(
            AgentTurnProductContextResolver contexts,
            AuthenticatedAgentTurnProjectVersionSourceFactory sources,
            ProjectStorageProperties storage,
            EngineGatewayProperties properties) {
        this.contexts = contexts;
        this.sources = sources;
        this.storage = storage;
        this.properties = properties;
        try {
            Path configured = Path.of(properties.getWorkspaceRoot());
            this.root = (configured.isAbsolute()
                    ? configured : Path.of("").toAbsolutePath().resolve(configured)).normalize()
                    .resolve("process-" + UUID.randomUUID());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("engine gateway workspace root is invalid");
        }
    }

    FileList list(EngineTaskAuthority authority) {
        BoundWorkspace bound = bind(authority);
        List<FileEntry> files = stats(bound).stream()
                .map(stat -> new FileEntry(stat.path().value(), stat.size(),
                        stat.hash().value(), mediaType(stat.path().value())))
                .toList();
        return new FileList("1.0", authority.taskId(), authority.projectVersion(), files);
    }

    FileRead read(EngineTaskAuthority authority, FileReadRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.path() == null || request.expectedSha256() == null
                || !request.expectedSha256().matches("[a-f0-9]{64}")) {
            throw EngineGatewayException.badRequest("WORKSPACE_READ_INVALID");
        }
        final ProjectPath path;
        try {
            path = new ProjectPath(request.path());
        } catch (RuntimeException invalid) {
            throw EngineGatewayException.badRequest("WORKSPACE_PATH_INVALID");
        }
        BoundWorkspace bound = bind(authority);
        WorkspaceFileStat stat = stats(bound).stream()
                .filter(value -> value.path().equals(path)).findFirst()
                .orElseThrow(() -> EngineGatewayException.notFound("WORKSPACE_FILE_NOT_FOUND"));
        if (!stat.hash().value().equals(request.expectedSha256())) {
            throw EngineGatewayException.conflict("WORKSPACE_FILE_HASH_CONFLICT");
        }
        if (stat.size() > properties.getMaxReadBytes()) {
            throw EngineGatewayException.tooLarge("WORKSPACE_FILE_TOO_LARGE");
        }
        byte[] bytes = bound.port().read(bound.materialized().workspace(), path);
        if (bytes.length != stat.size() || !sha256(bytes).equals(stat.hash().value())) {
            throw EngineGatewayException.conflict("WORKSPACE_FILE_CHANGED");
        }
        return new FileRead("1.0", path.value(), bytes.length, stat.hash().value(),
                mediaType(path.value()), "utf-8", utf8(bytes), false);
    }

    ResolvedInputs resolveInputs(EngineTaskAuthority authority, List<SandboxInput> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 64) {
            throw EngineGatewayException.badRequest("SANDBOX_INPUTS_INVALID");
        }
        BoundWorkspace bound = bind(authority);
        Map<String, WorkspaceFileStat> stats = new LinkedHashMap<>();
        stats(bound).forEach(value -> stats.put(value.path().value(), value));
        Map<String, String> files = new LinkedHashMap<>();
        List<ReceiptInput> inputs = new ArrayList<>();
        for (SandboxInput input : requested) {
            if (input == null || input.path() == null || input.sha256() == null
                    || !input.sha256().matches("[a-f0-9]{64}") || files.containsKey(input.path())) {
                throw EngineGatewayException.badRequest("SANDBOX_INPUTS_INVALID");
            }
            WorkspaceFileStat stat = stats.get(input.path());
            if (stat == null) throw EngineGatewayException.notFound("SANDBOX_INPUT_NOT_FOUND");
            if (!stat.hash().value().equals(input.sha256())) {
                throw EngineGatewayException.conflict("SANDBOX_INPUT_HASH_CONFLICT");
            }
            byte[] bytes = bound.port().read(bound.materialized().workspace(), stat.path());
            if (bytes.length != stat.size() || !sha256(bytes).equals(input.sha256())) {
                throw EngineGatewayException.conflict("SANDBOX_INPUT_CHANGED");
            }
            files.put(input.path(), utf8(bytes));
            inputs.add(new ReceiptInput(input.path(), input.sha256(), bytes.length));
        }
        inputs.sort(Comparator.comparing(ReceiptInput::path));
        return new ResolvedInputs(Map.copyOf(files), List.copyOf(inputs), fingerprint(inputs));
    }

    private BoundWorkspace bind(EngineTaskAuthority authority) {
        VerifiedAgentTurnProductContext context;
        try {
            context = contexts.resolve(authority.userId(), authority.turnId());
        } catch (RuntimeException rejected) {
            throw EngineGatewayException.forbidden("TASK_AUTHORITY_REJECTED");
        }
        if (!String.valueOf(authority.turnId()).equals(context.identity().sourceId())
                || !Long.valueOf(authority.userId()).equals(context.identity().userId())
                || !Long.valueOf(authority.sessionId()).equals(context.identity().sessionId())
                || !Long.valueOf(authority.projectId()).equals(context.identity().projectId())
                || context.projectVersionId().isEmpty()
                || !authority.projectVersion().equals(context.projectVersionId().orElseThrow())) {
            throw EngineGatewayException.conflict("TASK_PROJECT_VERSION_CHANGED");
        }
        WorkspaceMaterializationSpec spec = new WorkspaceMaterializationSpec(
                new WorkspaceId("engine." + authority.taskId().substring("task.".length())),
                new ProjectVersionRef(String.valueOf(authority.projectId()), authority.projectVersion()),
                new WorkspaceMaterializationLimits(
                        Math.min(storage.getMaxFileBytes(), 10L * 1024 * 1024),
                        storage.getMaxTotalBytes(),
                        Math.min(storage.getMaxFiles(), CONTRACT_MAX_FILES)));
        WorkspaceBinding binding = WorkspaceBinding.from(authority);
        try {
            BoundWorkspace bound = active.computeIfAbsent(authority.taskId(), ignored -> {
                WorkspacePort port = new LocalWorkspaceProvider(
                        root.resolve(authority.taskId()),
                        sources.create(authority.userId(), authority.turnId()));
                return new BoundWorkspace(binding, port, port.materialize(spec));
            });
            if (!bound.binding().equals(binding)) {
                throw EngineGatewayException.forbidden("TASK_AUTHORITY_CONFLICT");
            }
            return bound;
        } catch (EngineGatewayException failure) {
            throw failure;
        } catch (WorkspaceException failure) {
            throw materializationFailure(failure);
        } catch (RuntimeException failure) {
            throw EngineGatewayException.conflict("WORKSPACE_MATERIALIZATION_FAILED");
        }
    }

    private static EngineGatewayException materializationFailure(WorkspaceException failure) {
        return switch (failure.code()) {
            case FILE_LIMIT_EXCEEDED ->
                    EngineGatewayException.tooLarge("WORKSPACE_FILE_TOO_LARGE");
            case AGGREGATE_LIMIT_EXCEEDED ->
                    EngineGatewayException.tooLarge("WORKSPACE_TOTAL_TOO_LARGE");
            case FILE_COUNT_LIMIT_EXCEEDED ->
                    EngineGatewayException.tooLarge("WORKSPACE_FILE_LIMIT_EXCEEDED");
            default -> EngineGatewayException.conflict("WORKSPACE_MATERIALIZATION_FAILED");
        };
    }

    private static List<WorkspaceFileStat> stats(BoundWorkspace bound) {
        List<WorkspaceFileStat> values = bound.port().list(bound.materialized().workspace()).stream()
                .sorted(Comparator.comparing(value -> value.path().value())).toList();
        if (values.size() > CONTRACT_MAX_FILES) {
            throw EngineGatewayException.tooLarge("WORKSPACE_FILE_LIMIT_EXCEEDED");
        }
        return values;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception invalid) {
            throw EngineGatewayException.badRequest("WORKSPACE_FILE_NOT_UTF8");
        }
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "text/x-java-source";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".tex")) return "application/x-tex";
        return "text/plain";
    }

    private static String fingerprint(List<ReceiptInput> inputs) {
        StringBuilder value = new StringBuilder("agent-engine-inputs-v1");
        inputs.forEach(input -> value.append('\0').append(input.path())
                .append('\0').append(input.sha256()).append('\0').append(input.sizeBytes()));
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record ResolvedInputs(Map<String, String> files, List<ReceiptInput> inputs,
                          String inputFingerprint) { }
    private record WorkspaceBinding(
            String taskId, String requestDigest, long userId, long turnId,
            long sessionId, long projectId, String projectVersion,
            boolean readProject, boolean executeSandbox) {
        private static WorkspaceBinding from(EngineTaskAuthority authority) {
            return new WorkspaceBinding(
                    authority.taskId(), authority.requestDigest(), authority.userId(),
                    authority.turnId(), authority.sessionId(), authority.projectId(),
                    authority.projectVersion(), authority.readProject(),
                    authority.executeSandbox());
        }
    }
    private record BoundWorkspace(WorkspaceBinding binding, WorkspacePort port,
                                  VerifiedWorkspaceMaterialization materialized) { }
}
