package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileEntry;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileList;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileRead;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceWriteRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceWriteResult;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDocxCreateRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDocxCreateResult;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnProjectVersionSourceFactory;
import com.yanban.api.agent.v2.effect.project.V2ProjectStructuredReadFacade;
import com.yanban.api.project.AutomaticProjectFileChange;
import com.yanban.api.project.ProjectStorageProperties;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
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
    private static final int MAX_ACTIVE_DOCX_DRAFTS = 128;
    private final AgentTurnProductContextResolver contexts;
    private final AuthenticatedAgentTurnProjectVersionSourceFactory sources;
    private final ProjectStorageProperties storage;
    private final EngineGatewayProperties properties;
    private final ObjectMapper json;
    private final V2ProjectStructuredReadFacade structuredReads;
    private final Path root;
    private final ConcurrentMap<String, BoundWorkspace> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WriteFact> writes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GeneratedDocumentFact> generatedDocuments =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DocxDraft> docxDrafts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DocxCallFact> docxCalls = new ConcurrentHashMap<>();
    private final AgentEngineDocxGenerator docxGenerator = new AgentEngineDocxGenerator();

    AgentEngineWorkspaceGateway(
            AgentTurnProductContextResolver contexts,
            AuthenticatedAgentTurnProjectVersionSourceFactory sources,
            ProjectStorageProperties storage,
            EngineGatewayProperties properties,
            ObjectMapper json) {
        this.contexts = contexts;
        this.sources = sources;
        this.storage = storage;
        this.properties = properties;
        this.json = json;
        this.structuredReads = new V2ProjectStructuredReadFacade(json);
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
                || !request.expectedSha256().matches("[a-f0-9]{64}")
                || (request.maxLocations() != null
                && (request.maxLocations() < 1
                || request.maxLocations()
                > V2ProjectStructuredReadFacade.MAX_DOCUMENT_LOCATIONS))) {
            throw EngineGatewayException.badRequest("WORKSPACE_READ_INVALID");
        }
        final ProjectPath path;
        try {
            path = new ProjectPath(request.path());
        } catch (RuntimeException invalid) {
            throw EngineGatewayException.badRequest("WORKSPACE_PATH_INVALID");
        }
        if ((request.documentCursor() != null
                || request.maxLocations() != null)
                && !structuredReads.supports(path.value())) {
            throw EngineGatewayException.badRequest("WORKSPACE_READ_INVALID");
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
        if (structuredReads.supports(path.value())) {
            if (request.documentCursor() != null
                    && !structuredReads.supportsCursor(path.value())) {
                throw EngineGatewayException.badRequest(
                        "WORKSPACE_READ_INVALID");
            }
            try {
                var result = structuredReads.read(
                        bound.port(), bound.materialized().workspace(),
                        path.value(), request.documentCursor(),
                        request.maxLocations());
                return new FileRead("1.0", path.value(), bytes.length,
                        stat.hash().value(), mediaType(path.value()), "utf-8",
                        result.content(), result.truncated());
            } catch (V2ProjectStructuredReadFacade.ReadException failure) {
                throw EngineGatewayException.badRequest(
                        "WORKSPACE_STRUCTURED_READ_"
                                + failure.stage().toUpperCase(Locale.ROOT));
            }
        }
        return new FileRead("1.0", path.value(), bytes.length, stat.hash().value(),
                mediaType(path.value()), "utf-8", utf8(bytes), false);
    }

    synchronized WorkspaceWriteResult write(
            EngineTaskAuthority authority, WorkspaceWriteRequest request) {
        validateWrite(request);
        String key = authority.taskId() + "\0" + request.clientRequestId();
        WriteFact replay = writes.get(key);
        if (replay != null) {
            if (!replay.requestDigest().equals(request.requestDigest())) {
                throw EngineGatewayException.conflict("WORKSPACE_WRITE_DIGEST_CONFLICT");
            }
            WorkspaceWriteResult value = replay.result();
            return new WorkspaceWriteResult(value.contractVersion(), value.clientRequestId(),
                    value.requestDigest(), true, value.operation(), value.path(),
                    value.beforeSha256(), value.afterSha256(), value.sizeBytes());
        }
        final ProjectPath path;
        try {
            path = new ProjectPath(request.path());
        } catch (RuntimeException invalid) {
            throw EngineGatewayException.badRequest("WORKSPACE_PATH_INVALID");
        }
        if (readOnlyBinaryPath(path.value())) {
            throw EngineGatewayException.badRequest("WORKSPACE_BINARY_WRITE_REJECTED");
        }
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("operation", request.operation());
        semantics.put("path", request.path());
        semantics.put("baseSha256", request.baseSha256());
        semantics.put("content", request.content());
        if (!ReactPlanCanonicalJson.digest(json, semantics).equals(request.requestDigest())) {
            throw EngineGatewayException.badRequest("WORKSPACE_WRITE_DIGEST_INVALID");
        }
        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        if (content.length > properties.getMaxReadBytes()) {
            throw EngineGatewayException.tooLarge("WORKSPACE_FILE_TOO_LARGE");
        }
        BoundWorkspace bound = bind(authority);
        WorkspaceFileStat existing = stats(bound).stream()
                .filter(value -> value.path().equals(path)).findFirst().orElse(null);
        String before = existing == null ? null : existing.hash().value();
        String after = sha256(content);
        try {
            if ("ADD".equals(request.operation())) {
                if (request.baseSha256() != null || existing != null) {
                    throw EngineGatewayException.conflict("WORKSPACE_ADD_TARGET_EXISTS");
                }
                bound.port().create(bound.materialized().workspace(), path, content);
            } else {
                if (request.baseSha256() == null
                        || !request.baseSha256().matches("[a-f0-9]{64}")) {
                    throw EngineGatewayException.badRequest("WORKSPACE_BASE_HASH_INVALID");
                }
                if (existing == null) {
                    throw EngineGatewayException.notFound("WORKSPACE_FILE_NOT_FOUND");
                }
                if (!existing.hash().value().equals(request.baseSha256())) {
                    throw EngineGatewayException.conflict("WORKSPACE_FILE_HASH_CONFLICT");
                }
                if (existing.hash().value().equals(after)) {
                    throw EngineGatewayException.conflict("WORKSPACE_WRITE_NO_CHANGE");
                }
                bound.port().replace(bound.materialized().workspace(), path, content);
            }
        } catch (EngineGatewayException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw EngineGatewayException.conflict("WORKSPACE_WRITE_REJECTED");
        }
        WorkspaceFileStat written = bound.port().stat(bound.materialized().workspace(), path);
        if (!written.hash().value().equals(after) || written.size() != content.length) {
            throw EngineGatewayException.conflict("WORKSPACE_WRITE_ATTESTATION_FAILED");
        }
        WorkspaceWriteResult result = new WorkspaceWriteResult(
                "1.0", request.clientRequestId(), request.requestDigest(), false,
                request.operation(), path.value(), before, after, content.length);
        writes.put(key, new WriteFact(request.requestDigest(), result));
        return result;
    }

    synchronized WorkspaceDocxCreateResult createDocx(
            EngineTaskAuthority authority, WorkspaceDocxCreateRequest request) {
        validateDocxCreate(request);
        String key = authority.taskId() + "\0" + request.clientRequestId();
        DocxCallFact replay = docxCalls.get(key);
        if (replay != null) {
            if (!replay.requestDigest().equals(request.requestDigest())) {
                throw EngineGatewayException.conflict("WORKSPACE_WRITE_DIGEST_CONFLICT");
            }
            WorkspaceDocxCreateResult value = replay.result();
            return new WorkspaceDocxCreateResult(
                    value.contractVersion(), value.clientRequestId(),
                    value.requestDigest(), true, value.state(), value.path(),
                    value.totalBlocks(), value.operation(), value.beforeSha256(),
                    value.afterSha256(), value.sizeBytes());
        }
        final ProjectPath path;
        try {
            path = new ProjectPath(request.path());
        } catch (RuntimeException invalid) {
            throw EngineGatewayException.badRequest("WORKSPACE_PATH_INVALID");
        }
        if (!path.value().toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw EngineGatewayException.badRequest("WORKSPACE_DOCX_PATH_INVALID");
        }
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("mode", request.mode());
        semantics.put("path", request.path());
        semantics.put("title", request.title());
        semantics.put("author", request.author());
        semantics.put("styleProfile", request.styleProfile());
        semantics.put("blocks", request.blocks());
        if (!ReactPlanCanonicalJson.digest(json, semantics).equals(request.requestDigest())) {
            throw EngineGatewayException.badRequest("WORKSPACE_WRITE_DIGEST_INVALID");
        }
        String draftKey = authority.taskId() + "\0" + path.value();
        String mode = request.mode();
        if ("START".equals(mode)) {
            Instant expiresBefore = Instant.now().minusSeconds(2 * 60 * 60);
            docxDrafts.entrySet().removeIf(entry ->
                    entry.getValue().createdAt().isBefore(expiresBefore));
            if (docxDrafts.size() >= MAX_ACTIVE_DOCX_DRAFTS) {
                throw EngineGatewayException.tooLarge(
                        "WORKSPACE_DOCX_DRAFT_LIMIT_EXCEEDED");
            }
            BoundWorkspace bound = bind(authority);
            if (stats(bound).stream().anyMatch(value -> value.path().equals(path))
                    || docxDrafts.containsKey(draftKey)) {
                throw EngineGatewayException.conflict("WORKSPACE_ADD_TARGET_EXISTS");
            }
            DocxDraft draft = new DocxDraft(path.value(), request.title(),
                    request.author(), request.styleProfile(),
                    new ArrayList<>(request.blocks()), Instant.now());
            validateDraft(draft);
            docxDrafts.put(draftKey, draft);
            WorkspaceDocxCreateResult result = draftingResult(request, draft);
            docxCalls.put(key, new DocxCallFact(request.requestDigest(), result));
            return result;
        }
        if ("APPEND".equals(mode)) {
            DocxDraft draft = requireDraft(docxDrafts.get(draftKey));
            draft.blocks().addAll(request.blocks());
            try {
                validateDraft(draft);
            } catch (RuntimeException invalid) {
                draft.blocks().subList(draft.blocks().size() - request.blocks().size(),
                        draft.blocks().size()).clear();
                throw invalid;
            }
            WorkspaceDocxCreateResult result = draftingResult(request, draft);
            docxCalls.put(key, new DocxCallFact(request.requestDigest(), result));
            return result;
        }
        DocxDraft draft = "FINALIZE".equals(mode)
                ? requireDraft(docxDrafts.get(draftKey))
                : new DocxDraft(path.value(), request.title(), request.author(),
                request.styleProfile(), new ArrayList<>(), Instant.now());
        if (request.blocks() != null) draft.blocks().addAll(request.blocks());
        validateDraft(draft);
        WorkspaceDocxCreateRequest complete = new WorkspaceDocxCreateRequest(
                "1.0", request.clientRequestId(), request.requestDigest(),
                "CREATE", draft.path(), draft.title(), draft.author(),
                draft.styleProfile(), List.copyOf(draft.blocks()));
        BoundWorkspace bound = bind(authority);
        if (stats(bound).stream().anyMatch(value -> value.path().equals(path))) {
            throw EngineGatewayException.conflict("WORKSPACE_ADD_TARGET_EXISTS");
        }
        final AgentEngineDocxGenerator.GeneratedDocx generated;
        try {
            generated = docxGenerator.generate(complete);
        } catch (AgentEngineDocxGenerator.AgentEngineDocxException failure) {
            throw EngineGatewayException.badRequest(failure.code());
        }
        byte[] content = generated.bytes();
        if (content.length > properties.getMaxReadBytes()) {
            throw EngineGatewayException.tooLarge("WORKSPACE_FILE_TOO_LARGE");
        }
        String after = sha256(content);
        try {
            bound.port().create(bound.materialized().workspace(), path, content);
        } catch (RuntimeException failure) {
            throw EngineGatewayException.conflict("WORKSPACE_WRITE_REJECTED");
        }
        WorkspaceFileStat written = bound.port().stat(bound.materialized().workspace(), path);
        if (!written.hash().value().equals(after) || written.size() != content.length) {
            throw EngineGatewayException.conflict("WORKSPACE_WRITE_ATTESTATION_FAILED");
        }
        WorkspaceDocxCreateResult result = new WorkspaceDocxCreateResult(
                "1.0", request.clientRequestId(), request.requestDigest(), false,
                "COMPLETED", path.value(), draft.blocks().size(), "ADD",
                null, after, (long) content.length);
        generatedDocuments.put(authority.taskId() + "\0" + path.value(),
                new GeneratedDocumentFact(after, generated.mediaType(),
                        "docx-generation." + after));
        if ("FINALIZE".equals(mode)) docxDrafts.remove(draftKey);
        docxCalls.put(key, new DocxCallFact(request.requestDigest(), result));
        return result;
    }

    AgentEngineGatewayDtos.WorkspaceDiff diff(EngineTaskAuthority authority) {
        BoundWorkspace bound = bind(authority);
        var source = bound.port().diff(bound.materialized().workspace(),
                new DiffId("engine-diff." + authority.taskId().substring("task.".length())),
                Instant.EPOCH);
        List<AgentEngineGatewayDtos.WorkspaceDiffEntry> entries = source.entries().stream()
                .filter(entry -> entry.kind() == DiffKind.ADD || entry.kind() == DiffKind.MODIFY)
                .map(entry -> new AgentEngineGatewayDtos.WorkspaceDiffEntry(
                        entry.kind().name(), entry.path().value(),
                        entry.beforeHash().map(value -> value.value()).orElse(null),
                        entry.afterHash().map(value -> value.value()).orElse(null)))
                .toList();
        if (entries.size() != source.entries().size()) {
            throw EngineGatewayException.conflict("WORKSPACE_DIFF_UNSUPPORTED");
        }
        return new AgentEngineGatewayDtos.WorkspaceDiff(
                "1.0", authority.taskId(), authority.projectVersion(),
                !entries.isEmpty(), entries);
    }

    List<AutomaticProjectFileChange> publicationChanges(
            EngineTaskAuthority authority,
            List<AgentEngineGatewayDtos.WorkspaceDiffEntry> requested) {
        AgentEngineGatewayDtos.WorkspaceDiff actual = diff(authority);
        if (!actual.changed() || requested == null
                || !actual.entries().equals(requested)) {
            throw EngineGatewayException.conflict("WORKSPACE_PUBLICATION_DIFF_CONFLICT");
        }
        BoundWorkspace bound = bind(authority);
        Map<String, WorkspaceFileStat> current = new LinkedHashMap<>();
        stats(bound).forEach(stat -> current.put(stat.path().value(), stat));
        List<AutomaticProjectFileChange> changes = new ArrayList<>();
        for (AgentEngineGatewayDtos.WorkspaceDiffEntry entry : actual.entries()) {
            WorkspaceFileStat stat = current.get(entry.path());
            if (stat == null || !stat.hash().value().equals(entry.afterSha256())) {
                throw EngineGatewayException.conflict("WORKSPACE_PUBLICATION_FILE_CHANGED");
            }
            byte[] bytes = bound.port().read(bound.materialized().workspace(), stat.path());
            if (bytes.length != stat.size() || !sha256(bytes).equals(entry.afterSha256())) {
                throw EngineGatewayException.conflict("WORKSPACE_PUBLICATION_FILE_CHANGED");
            }
            GeneratedDocumentFact generated = generatedDocuments.get(
                    authority.taskId() + "\0" + entry.path());
            if (generated != null && generated.sha256().equals(entry.afterSha256())) {
                changes.add(AutomaticProjectFileChange.generatedDocx(
                        entry.operation(), entry.path(), entry.beforeSha256(),
                        entry.afterSha256(), bytes, generated.mediaType(),
                        generated.attestationRef()));
            } else {
                changes.add(new AutomaticProjectFileChange(
                        entry.operation(), entry.path(), entry.beforeSha256(),
                        entry.afterSha256(), utf8(bytes)));
            }
        }
        return List.copyOf(changes);
    }

    ResolvedInputs resolveInputs(
            EngineTaskAuthority authority,
            List<String> argv,
            List<SandboxInput> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 64) {
            throw EngineGatewayException.badRequest("SANDBOX_INPUTS_INVALID");
        }
        BoundWorkspace bound = bind(authority);
        List<WorkspaceFileStat> orderedStats = stats(bound);
        Map<String, WorkspaceFileStat> stats = new LinkedHashMap<>();
        orderedStats.forEach(value -> stats.put(value.path().value(), value));
        Map<String, SandboxInput> anchors = new LinkedHashMap<>();
        for (SandboxInput input : requested) {
            if (input == null || input.path() == null || input.sha256() == null
                    || !input.sha256().matches("[a-f0-9]{64}")
                    || anchors.putIfAbsent(input.path(), input) != null) {
                throw EngineGatewayException.badRequest("SANDBOX_INPUTS_INVALID");
            }
            WorkspaceFileStat stat = stats.get(input.path());
            if (stat == null) {
                throw EngineGatewayException.notFound("SANDBOX_INPUT_NOT_FOUND");
            }
            verifyBytes(bound, stat, input.sha256());
        }
        if (argv != null && !argv.isEmpty() && "mvn".equals(argv.get(0))) {
            return resolveMavenInputs(authority, bound, orderedStats, anchors);
        }
        Map<String, String> files = new LinkedHashMap<>();
        List<ReceiptInput> inputs = new ArrayList<>();
        for (SandboxInput input : requested) {
            WorkspaceFileStat stat = stats.get(input.path());
            byte[] bytes = verifyBytes(bound, stat, input.sha256());
            files.put(input.path(), utf8(bytes));
            inputs.add(new ReceiptInput(input.path(), input.sha256(), bytes.length));
        }
        inputs.sort(Comparator.comparing(ReceiptInput::path));
        return new ResolvedInputs(Map.copyOf(files), List.copyOf(inputs), fingerprint(inputs));
    }

    private ResolvedInputs resolveMavenInputs(
            EngineTaskAuthority authority,
            BoundWorkspace bound,
            List<WorkspaceFileStat> orderedStats,
            Map<String, SandboxInput> anchors) {
        if (orderedStats.stream().noneMatch(stat -> "pom.xml".equals(stat.path().value()))) {
            throw EngineGatewayException.badRequest("MAVEN_ROOT_POM_REQUIRED");
        }
        AgentEngineGatewayDtos.WorkspaceDiff workspaceDiff = diff(authority);
        for (AgentEngineGatewayDtos.WorkspaceDiffEntry change : workspaceDiff.entries()) {
            if (readOnlyBinaryPath(change.path())) {
                throw EngineGatewayException.badRequest(
                        "MAVEN_BINARY_RESOURCE_UNSUPPORTED");
            }
            SandboxInput anchor = anchors.get(change.path());
            if (anchor == null || !change.afterSha256().equals(anchor.sha256())) {
                throw EngineGatewayException.badRequest(
                        "MAVEN_CHANGED_INPUT_MISSING");
            }
        }
        if (orderedStats.stream().map(stat -> stat.path().value())
                .anyMatch(AgentEngineWorkspaceGateway::unsupportedMavenBinaryResource)) {
            throw EngineGatewayException.badRequest(
                    "MAVEN_BINARY_RESOURCE_UNSUPPORTED");
        }
        Map<String, String> files = new LinkedHashMap<>();
        List<ReceiptInput> inputs = new ArrayList<>();
        long totalBytes = 0;
        for (WorkspaceFileStat stat : orderedStats) {
            String path = stat.path().value();
            if (readOnlyBinaryPath(path)) {
                continue;
            }
            if (inputs.size() >= properties.getMaxSandboxContextFiles()
                    || stat.size() > properties.getMaxSandboxContextFileBytes()
                    || stat.size() > properties.getMaxSandboxContextBytes() - totalBytes) {
                throw EngineGatewayException.tooLarge(
                        "MAVEN_CONTEXT_LIMIT_EXCEEDED");
            }
            byte[] bytes = verifyBytes(bound, stat, stat.hash().value());
            files.put(path, utf8(bytes));
            inputs.add(new ReceiptInput(path, stat.hash().value(), bytes.length));
            totalBytes += bytes.length;
        }
        return new ResolvedInputs(
                Map.copyOf(files), List.copyOf(inputs), fingerprint(inputs));
    }

    private static byte[] verifyBytes(
            BoundWorkspace bound, WorkspaceFileStat stat, String expectedSha256) {
        if (!stat.hash().value().equals(expectedSha256)) {
            throw EngineGatewayException.conflict("SANDBOX_INPUT_HASH_CONFLICT");
        }
        byte[] bytes = bound.port().read(bound.materialized().workspace(), stat.path());
        if (bytes.length != stat.size() || !sha256(bytes).equals(expectedSha256)) {
            throw EngineGatewayException.conflict("SANDBOX_INPUT_CHANGED");
        }
        return bytes;
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
        try {
            return active.computeIfAbsent(authority.taskId(), ignored -> {
                WorkspacePort port = new LocalWorkspaceProvider(
                        root.resolve(authority.taskId()),
                        sources.create(authority.userId(), authority.turnId()));
                return new BoundWorkspace(port, port.materialize(spec));
            });
        } catch (RuntimeException failure) {
            throw EngineGatewayException.conflict("WORKSPACE_MATERIALIZATION_FAILED");
        }
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

    private static void validateWrite(WorkspaceWriteRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.clientRequestId() == null
                || !request.clientRequestId().matches("call\\.[A-Za-z0-9_-]{16,120}")
                || request.requestDigest() == null
                || !request.requestDigest().matches("[a-f0-9]{64}")
                || !("ADD".equals(request.operation()) || "MODIFY".equals(request.operation()))
                || request.path() == null || request.content() == null) {
            throw EngineGatewayException.badRequest("WORKSPACE_WRITE_INVALID");
        }
    }

    private static void validateDocxCreate(WorkspaceDocxCreateRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.clientRequestId() == null
                || !request.clientRequestId().matches("call\\.[A-Za-z0-9_-]{16,120}")
                || request.requestDigest() == null
                || !request.requestDigest().matches("[a-f0-9]{64}")
                || !("CREATE".equals(request.mode()) || "START".equals(request.mode())
                || "APPEND".equals(request.mode()) || "FINALIZE".equals(request.mode()))
                || request.path() == null
                || (("CREATE".equals(request.mode()) || "START".equals(request.mode())
                || "APPEND".equals(request.mode()))
                && (request.blocks() == null || request.blocks().isEmpty()))
                || ("FINALIZE".equals(request.mode()) && request.blocks() == null)
                || (("CREATE".equals(request.mode()) || "START".equals(request.mode()))
                && request.styleProfile() == null)
                || (("APPEND".equals(request.mode()) || "FINALIZE".equals(request.mode()))
                && (request.title() != null || request.author() != null
                || request.styleProfile() != null))) {
            throw EngineGatewayException.badRequest("WORKSPACE_DOCX_INVALID");
        }
    }

    private void validateDraft(DocxDraft draft) {
        try {
            docxGenerator.validate(new WorkspaceDocxCreateRequest(
                    "1.0", "call." + "v".repeat(16), "0".repeat(64),
                    "CREATE", draft.path(), draft.title(), draft.author(),
                    draft.styleProfile(), List.copyOf(draft.blocks())));
        } catch (AgentEngineDocxGenerator.AgentEngineDocxException failure) {
            throw EngineGatewayException.badRequest(failure.code());
        }
    }

    private static DocxDraft requireDraft(DocxDraft draft) {
        if (draft == null) {
            throw EngineGatewayException.conflict("WORKSPACE_DOCX_DRAFT_NOT_FOUND");
        }
        return draft;
    }

    private static WorkspaceDocxCreateResult draftingResult(
            WorkspaceDocxCreateRequest request, DocxDraft draft) {
        return new WorkspaceDocxCreateResult(
                "1.0", request.clientRequestId(), request.requestDigest(), false,
                "DRAFTING", draft.path(), draft.blocks().size(), null,
                null, null, null);
    }

    private static boolean readOnlyBinaryPath(String path) {
        return path.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|doc|docx|xlsx)$");
    }

    private static boolean unsupportedMavenBinaryResource(String path) {
        if (!readOnlyBinaryPath(path)) {
            return false;
        }
        String normalized = "/" + path.toLowerCase(Locale.ROOT) + "/";
        return normalized.contains("/src/main/resources/")
                || normalized.contains("/src/test/resources/");
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "text/x-java-source";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".tex")) return "application/x-tex";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
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
    private record WriteFact(String requestDigest, WorkspaceWriteResult result) { }
    private record GeneratedDocumentFact(
            String sha256, String mediaType, String attestationRef) { }
    private record DocxCallFact(
            String requestDigest, WorkspaceDocxCreateResult result) { }
    private record DocxDraft(String path, String title, String author,
                             String styleProfile,
                             List<AgentEngineGatewayDtos.DocxBlock> blocks,
                             Instant createdAt) { }
    private record BoundWorkspace(WorkspacePort port,
                                  VerifiedWorkspaceMaterialization materialized) { }
}
