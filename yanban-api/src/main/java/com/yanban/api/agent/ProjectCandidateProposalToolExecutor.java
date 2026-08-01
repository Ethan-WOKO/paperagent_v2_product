package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Proposal-only model tool. It persists a review artifact and never writes Project content. */
@Component
public final class ProjectCandidateProposalToolExecutor implements ToolExecutor {
    public static final String TOOL_NAME = "project_propose_candidate";
    public static final String REQUIRED_PERMISSION = "project:candidate-propose";
    public static final String CAPABILITY_DOMAIN = "project-candidate-proposal";
    private static final Set<String> ROOT_FIELDS = Set.of("changes");
    private static final Set<String> CHANGE_FIELDS = Set.of(
            "type", "relativePath", "baseFileHash", "replacementText", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "relativePath", "fileHash", "startLine", "endLine", "parserVersion");

    private final ProjectService projects;
    private final CandidateChangeArtifactService candidates;
    private final CandidateProposalEvidenceSelector evidenceSelector;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ProjectCandidateProposalToolExecutor(ProjectService projects,
                                                CandidateChangeArtifactService candidates,
                                                CandidateProposalEvidenceSelector evidenceSelector,
                                                ObjectMapper objectMapper) {
        this.projects = projects;
        this.candidates = candidates;
        this.evidenceSelector = evidenceSelector;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition(TOOL_NAME,
                "Create a validated read-only multi-file Candidate from full replacement text and exact portable Evidence provenance. "
                        + "Every Evidence selector must repeat the complete path, hash, startLine, endLine, and parserVersion "
                        + "from one completed Project read/search observation exactly; selecting a subrange of a larger read is rejected, "
                        + "so first re-read the exact range you intend to cite. This never applies changes.",
                schema(objectMapper));
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(TOOL_NAME, "v1", CAPABILITY_DOMAIN,
                List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of(REQUIRED_PERMISSION),
                List.of(ToolDescriptor.ResourceScope.PROJECT), ToolDescriptor.SideEffectType.CREATE,
                ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        CandidateProposalExecutionScope.Context scope = CandidateProposalExecutionScope.current();
        if (call == null || !TOOL_NAME.equals(call.name()) || scope == null
                || !scope.allowedTools().contains(TOOL_NAME) || !ToolExecutionContext.isToolAllowed(TOOL_NAME)
                || !scope.userId().equals(ToolExecutionContext.getCurrentUserId())
                || !scope.projectId().equals(ToolExecutionContext.getCurrentProjectId())) {
            return failure(call, ToolErrorCode.PERMISSION_DENIED,
                    "Candidate proposal requires an authenticated Project proposal scope.");
        }
        try {
            ParsedProposal parsed = parse(call.arguments());
            ProjectManifestResponse manifest = projects.manifest(scope.userId(), scope.projectId());
            ProjectVersionRef currentVersion = new ProjectVersionRef(manifest.version());
            CandidateProposalEvidenceSelector.Selection selection = evidenceSelector.select(
                    scope, currentVersion, parsed.selectors());
            List<CandidateIntent.FileIntent> changes = new ArrayList<>();
            for (int index = 0; index < parsed.changes().size(); index++) {
                ParsedChange change = parsed.changes().get(index);
                changes.add(new CandidateIntent.FileIntent(change.type(), change.relativePath(),
                        change.baseFileHash(), change.replacementText(), selection.evidenceIds().get(index)));
            }
            CandidateArtifactResponse response = candidates.store(scope.userId(), scope.sessionId(),
                    new ProjectRuntimeContext(scope.userId(), scope.projectId()),
                    new CandidateIntent(scope.projectId(), currentVersion, changes), selection.ledger());
            return new ToolResult(call.id(), TOOL_NAME, true, objectMapper.valueToTree(response),
                    null, null, false, List.of(), List.of(),
                    List.of("proposal-only", "NOT_APPLIED"), currentVersion.value());
        } catch (ResponseStatusException ex) {
            ToolErrorCode code = ex.getStatusCode() == HttpStatus.CONFLICT ? ToolErrorCode.CONFLICT
                    : ex.getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE ? ToolErrorCode.RATE_LIMITED
                    : ex.getStatusCode() == HttpStatus.FORBIDDEN || ex.getStatusCode() == HttpStatus.UNAUTHORIZED
                    ? ToolErrorCode.PERMISSION_DENIED : ToolErrorCode.VALIDATION_ERROR;
            return failure(call, code, safeMessage(ex.getReason()));
        } catch (IllegalArgumentException ex) {
            return failure(call, ToolErrorCode.VALIDATION_ERROR, safeMessage(ex.getMessage()));
        } catch (RuntimeException ex) {
            return failure(call, ToolErrorCode.INTERNAL_ERROR, "Candidate proposal failed closed.");
        }
    }

    private ParsedProposal parse(JsonNode arguments) {
        ObjectNode root = requireObject(arguments, "proposal arguments");
        requireExactFields(root, ROOT_FIELDS, "proposal arguments");
        JsonNode values = root.get("changes");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("changes must be a non-empty array");
        }
        List<ParsedChange> changes = new ArrayList<>();
        List<List<CandidateProposalEvidenceSelector.PortableEvidenceSelector>> selectors = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode value : values) {
            ObjectNode change = requireObject(value, "change");
            requireExactFields(change, CHANGE_FIELDS, "change");
            CandidateIntent.Type type = parseType(text(change, "type"));
            ProjectRelativePath path = new ProjectRelativePath(text(change, "relativePath"));
            if (!paths.add(path.value().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("changes contain duplicate target paths");
            }
            String baseValue = optionalText(change, "baseFileHash");
            FileHash base = baseValue == null ? null : new FileHash(baseValue);
            String replacement = change.has("replacementText") && !change.get("replacementText").isNull()
                    ? text(change, "replacementText") : null;
            changes.add(new ParsedChange(type, path, base, replacement));
            selectors.add(parseEvidence(change.get("evidence")));
        }
        return new ParsedProposal(List.copyOf(changes), List.copyOf(selectors));
    }

    private List<CandidateProposalEvidenceSelector.PortableEvidenceSelector> parseEvidence(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("each change requires at least one Evidence selector");
        }
        List<CandidateProposalEvidenceSelector.PortableEvidenceSelector> selectors = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode selector = requireObject(value, "Evidence selector");
            requireExactFields(selector, EVIDENCE_FIELDS, "Evidence selector");
            selectors.add(new CandidateProposalEvidenceSelector.PortableEvidenceSelector(
                    new ProjectRelativePath(text(selector, "relativePath")),
                    new FileHash(text(selector, "fileHash")), positiveInt(selector, "startLine"),
                    positiveInt(selector, "endLine"), text(selector, "parserVersion")));
        }
        return List.copyOf(selectors);
    }

    private CandidateIntent.Type parseType(String value) {
        try { return CandidateIntent.Type.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("type must be ADD, MODIFY, or DELETE"); }
    }

    private ObjectNode requireObject(JsonNode value, String label) {
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(label + " must be an object");
        return object;
    }

    private void requireExactFields(ObjectNode value, Set<String> allowed, String label) {
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException(label + " contains unsupported field: " + field);
        });
    }

    private String text(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return node.textValue();
    }

    private String optionalText(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException(field + " must be a string when supplied");
        if (node.textValue().isBlank()) return null;
        return node.textValue();
    }

    private int positiveInt(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return node.intValue();
    }

    private ToolResult failure(ToolCall call, ToolErrorCode code, String message) {
        return ToolResult.failure(call == null ? null : call.id(), TOOL_NAME, code, message);
    }

    private String safeMessage(String value) {
        if (value == null || value.isBlank()) return "Candidate proposal was rejected.";
        String normalized = value.replaceAll("[\\r\\n]+", " ");
        if (normalized.matches("(?s).*[A-Za-z]:[\\\\/].*") || normalized.contains("/home/")
                || normalized.contains("/tmp/") || normalized.contains("file:/")) {
            return "Candidate proposal was rejected.";
        }
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode evidence = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode evidenceProperties = evidence.putObject("properties");
        evidenceProperties.putObject("relativePath").put("type", "string");
        evidenceProperties.putObject("fileHash").put("type", "string");
        evidenceProperties.putObject("startLine").put("type", "integer")
                .put("description", "Must exactly equal startLine from one completed Project observation; subranges are rejected.");
        evidenceProperties.putObject("endLine").put("type", "integer")
                .put("description", "Must exactly equal endLine from the same completed Project observation; subranges are rejected.");
        evidenceProperties.putObject("parserVersion").put("type", "string")
                .put("description", "Must exactly repeat parserVersion from that completed Project observation.");
        evidence.putArray("required").add("relativePath").add("fileHash").add("startLine")
                .add("endLine").add("parserVersion");
        ObjectNode change = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = change.putObject("properties");
        properties.putObject("type").put("type", "string").put("description", "ADD, MODIFY, or DELETE.");
        properties.putObject("relativePath").put("type", "string");
        properties.putObject("baseFileHash").put("type", "string");
        properties.putObject("replacementText").put("type", "string")
                .put("description", "Full UTF-8 replacement text; omit for DELETE.");
        properties.putObject("evidence").put("type", "array").set("items", evidence);
        change.putArray("required").add("type").add("relativePath").add("evidence");
        ObjectNode root = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        root.putObject("properties").putObject("changes").put("type", "array").set("items", change);
        root.putArray("required").add("changes");
        return root;
    }

    private record ParsedProposal(List<ParsedChange> changes,
                                  List<List<CandidateProposalEvidenceSelector.PortableEvidenceSelector>> selectors) { }
    private record ParsedChange(CandidateIntent.Type type, ProjectRelativePath relativePath,
                                FileHash baseFileHash, String replacementText) { }
}
