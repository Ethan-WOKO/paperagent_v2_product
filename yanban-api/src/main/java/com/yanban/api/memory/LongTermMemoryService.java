package com.yanban.api.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LongTermMemoryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_TAGS = 12;
    private static final int MAX_TAG_CHARACTERS = 64;
    private static final String STATUS_ALL = "ALL";
    private static final Set<String> SAFE_MEMORY_TYPES = Set.of(
            "PREFERENCE", "RESEARCH_PROFILE", "RESEARCH_FIELD", "STYLE", "FACT", "WARNING", "DECISION", "TERMINOLOGY");
    private static final Pattern PROJECT_VERSION_PATTERN = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
            "(?i)(?:(?:^|[\\s\\(\\\"'=])[a-z]:[\\\\/]|\\\\\\\\[^\\s]+|file://|"
                    + "(?:^|[\\s\\(\\\"'=])/(?:home|users?|var|etc|opt|tmp|workspace|mnt|root|data)(?:/|$))");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(?:(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|password|secret|private[_ -]?key)"
                    + "\\s*(?::|=|\\bis\\b)\\s*\\S+|\\bbearer\\s+[a-z0-9._~+/-]{8,}|\\bsk-[a-z0-9_-]{8,})");

    private final AgentLongTermMemoryRepository memories;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;

    public LongTermMemoryService(AgentLongTermMemoryRepository memories,
                                 ObjectMapper objectMapper,
                                 ProjectService projectService) {
        this.memories = memories;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
    }

    @Transactional(readOnly = true)
    public List<LongTermMemoryResponse> listMemories(Long userId, String status, Integer limit) {
        String normalizedStatus = normalizeStatus(status);
        PageRequest page = PageRequest.of(0, safeLimit(limit));
        List<AgentLongTermMemory> rows = STATUS_ALL.equals(normalizedStatus)
                ? memories.findByUserIdOrderByUpdatedAtDesc(userId, page)
                : memories.findActiveForGovernance(userId, Instant.now(), page);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LongTermMemoryResponse getMemory(Long userId, Long memoryId) {
        return toResponse(getOwnedMemory(userId, memoryId));
    }

    @Transactional
    public LongTermMemoryResponse createMemory(Long userId, CreateLongTermMemoryRequest request) {
        String scope = normalizeScope(request.scope());
        Long projectId = resolveCreateProjectId(scope, request.projectId());
        String memoryType = normalizeMemoryType(request.memoryType());
        validateSafeText(request.content(), "memory content");
        String tagsJson = writeTags(request.tags());
        AgentLongTermMemory memory = new AgentLongTermMemory(
                userId,
                projectId,
                scope,
                memoryType,
                request.content(),
                tagsJson,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null,
                request.confidence(),
                null
        );
        if (AgentLongTermMemory.SCOPE_PROJECT.equals(scope)) {
            memory.bindProjectVersion(resolveCurrentProjectVersion(userId, projectId));
        }
        return toResponse(memories.saveAndFlush(memory));
    }

    @Transactional
    DistilledMemoryWriteResult createDistilledMemory(Long userId, MemoryDistillationCandidate candidate) {
        String scope = normalizeScope(candidate.scope());
        Long projectId = resolveCreateProjectId(scope, candidate.projectId());
        String memoryType = normalizeMemoryType(candidate.memoryType());
        if (candidate.content().length() > 1_200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "distilled memory content is too long");
        }
        validateSafeText(candidate.content(), "memory content");
        String tagsJson = writeTags(candidate.tags());
        String sourceRefId = "distilled:" + distilledFingerprint(userId, scope, projectId, memoryType, candidate.content());
        var existing = memories.findByUserIdAndSourceTypeAndSourceRefId(
                userId, AgentLongTermMemory.SOURCE_LLM_DISTILLED, sourceRefId);
        if (existing.isPresent()) {
            return new DistilledMemoryWriteResult(existing.get(), false);
        }
        AgentLongTermMemory memory = new AgentLongTermMemory(
                userId,
                projectId,
                scope,
                memoryType,
                candidate.content(),
                tagsJson,
                AgentLongTermMemory.SOURCE_LLM_DISTILLED,
                sourceRefId,
                candidate.confidence(),
                null
        );
        if (AgentLongTermMemory.SCOPE_PROJECT.equals(scope)) {
            memory.bindProjectVersion(resolveCurrentProjectVersion(userId, projectId));
        }
        return new DistilledMemoryWriteResult(memories.saveAndFlush(memory), true);
    }

    @Transactional
    public LongTermMemoryResponse correctMemory(Long userId, Long memoryId, UpdateLongTermMemoryRequest request) {
        Instant now = Instant.now();
        AgentLongTermMemory current = getOwnedMemoryForUpdate(userId, memoryId);
        requireActionable(current, now, "corrected");
        rejectIdentityOverride(current, request);
        String memoryType = request.memoryType() == null
                ? current.getMemoryType() : normalizeMemoryType(request.memoryType());
        validateSafeText(request.content(), "memory content");
        String tagsJson = request.tags() == null ? current.getTagsJson() : writeTags(request.tags());
        String projectVersion = requireCurrentProjectVersion(userId, current);
        AgentLongTermMemory replacement = new AgentLongTermMemory(
                userId,
                current.getProjectId(),
                current.getScope(),
                memoryType,
                request.content(),
                tagsJson,
                AgentLongTermMemory.SOURCE_USER_CORRECTED,
                "memory:" + current.getId(),
                request.confidence() == null ? current.getConfidence() : request.confidence(),
                current.getId()
        );
        if (projectVersion != null) {
            replacement.bindProjectVersion(projectVersion);
        }
        AgentLongTermMemory saved = memories.saveAndFlush(replacement);
        saved.confirm(now,
                AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION,
                AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION,
                provenanceRef(saved.getId(), "correct"));
        saved = memories.saveAndFlush(saved);
        current.markSuperseded(saved.getId());
        memories.saveAndFlush(current);
        return toResponse(saved);
    }

    @Transactional
    public LongTermMemoryResponse confirmMemory(Long userId, Long memoryId) {
        Instant now = Instant.now();
        AgentLongTermMemory memory = getOwnedMemoryForUpdate(userId, memoryId);
        requireActionable(memory, now, "confirmed");
        requireCurrentProjectVersion(userId, memory);
        if (AgentLongTermMemory.CONFIRMATION_REJECTED.equals(memory.getConfirmationStatus())) {
            throw conflict("rejected memory cannot be confirmed");
        }
        if (AgentLongTermMemory.CONFIRMATION_CONFIRMED.equals(memory.getConfirmationStatus())) {
            return toResponse(memory);
        }
        if (!AgentLongTermMemory.CONFIRMATION_UNCONFIRMED.equals(memory.getConfirmationStatus())) {
            throw conflict("memory confirmation state is invalid");
        }
        memory.confirm(now,
                AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION,
                AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION,
                provenanceRef(memory.getId(), "confirm"));
        return toResponse(memories.saveAndFlush(memory));
    }

    @Transactional
    public LongTermMemoryResponse rejectMemory(Long userId, Long memoryId) {
        Instant now = Instant.now();
        AgentLongTermMemory memory = getOwnedMemoryForUpdate(userId, memoryId);
        requireActiveAndUninvalidated(memory, "rejected");
        requireOwnedIdentity(userId, memory);
        if (memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now)) {
            throw conflict("expired memory cannot be rejected");
        }
        if (AgentLongTermMemory.CONFIRMATION_CONFIRMED.equals(memory.getConfirmationStatus())) {
            throw conflict("confirmed memory cannot be rejected");
        }
        if (AgentLongTermMemory.CONFIRMATION_REJECTED.equals(memory.getConfirmationStatus())) {
            return toResponse(memory);
        }
        if (!AgentLongTermMemory.CONFIRMATION_UNCONFIRMED.equals(memory.getConfirmationStatus())) {
            throw conflict("memory confirmation state is invalid");
        }
        memory.reject();
        return toResponse(memories.saveAndFlush(memory));
    }

    @Transactional
    public LongTermMemoryResponse updateExpiry(Long userId,
                                               Long memoryId,
                                               UpdateLongTermMemoryExpiryRequest request) {
        AgentLongTermMemory memory = getOwnedMemoryForUpdate(userId, memoryId);
        requireActiveAndUninvalidated(memory, "updated");
        requireOwnedIdentity(userId, memory);
        Instant expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }
        memory.setExpiresAt(expiresAt);
        return toResponse(memories.saveAndFlush(memory));
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        AgentLongTermMemory memory = getOwnedMemoryForUpdate(userId, memoryId);
        requireOwnedIdentity(userId, memory);
        if (AgentLongTermMemory.STATUS_DELETED.equals(memory.getStatus())) {
            return;
        }
        if (AgentLongTermMemory.STATUS_SUPERSEDED.equals(memory.getStatus())
                || memory.getSupersededByMemoryId() != null) {
            throw conflict("superseded memory cannot be deleted");
        }
        if (!AgentLongTermMemory.STATUS_ACTIVE.equals(memory.getStatus())) {
            throw conflict("memory cannot be deleted in its current state");
        }
        memory.markDeleted();
        memories.saveAndFlush(memory);
    }

    private AgentLongTermMemory getOwnedMemory(Long userId, Long memoryId) {
        return memories.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
    }

    private AgentLongTermMemory getOwnedMemoryForUpdate(Long userId, Long memoryId) {
        return memories.findOwnedForUpdate(memoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
    }

    private void requireActionable(AgentLongTermMemory memory, Instant now, String action) {
        requireActiveAndUninvalidated(memory, action);
        if (memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now)) {
            throw conflict("expired memory cannot be " + action);
        }
    }

    private void requireActiveAndUninvalidated(AgentLongTermMemory memory, String action) {
        if (!AgentLongTermMemory.STATUS_ACTIVE.equals(memory.getStatus())
                || memory.getDeletedAt() != null
                || memory.getSupersededByMemoryId() != null) {
            throw conflict("only active memory can be " + action);
        }
        if (memory.getInvalidatedAt() != null) {
            throw conflict("invalidated memory cannot be " + action);
        }
    }

    private void rejectIdentityOverride(AgentLongTermMemory current, UpdateLongTermMemoryRequest request) {
        if (StringUtils.hasText(request.scope())
                && !current.getScope().equals(request.scope().trim().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memory scope cannot be changed");
        }
        if (request.projectId() != null && !Objects.equals(current.getProjectId(), request.projectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memory project cannot be changed");
        }
        if (AgentLongTermMemory.SCOPE_USER.equals(current.getScope()) && request.projectId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER memory cannot have projectId");
        }
    }

    private Long resolveCreateProjectId(String scope, Long projectId) {
        if (AgentLongTermMemory.SCOPE_USER.equals(scope)) {
            if (projectId != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER memory cannot have projectId");
            }
            return null;
        }
        if (projectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PROJECT memory requires projectId");
        }
        return projectId;
    }

    private String requireCurrentProjectVersion(Long userId, AgentLongTermMemory memory) {
        String currentVersion = requireOwnedIdentity(userId, memory);
        if (AgentLongTermMemory.SCOPE_USER.equals(memory.getScope())) {
            return null;
        }
        if (!currentVersion.equals(memory.getProjectVersion())) {
            throw conflict("PROJECT memory is stale for the current Project version");
        }
        return currentVersion;
    }

    private String requireOwnedIdentity(Long userId, AgentLongTermMemory memory) {
        if (AgentLongTermMemory.SCOPE_USER.equals(memory.getScope())) {
            if (memory.getProjectId() != null || memory.getProjectVersion() != null) {
                throw conflict("USER memory identity is invalid");
            }
            return null;
        }
        if (!AgentLongTermMemory.SCOPE_PROJECT.equals(memory.getScope()) || memory.getProjectId() == null
                || !isProjectVersion(memory.getProjectVersion())) {
            throw conflict("PROJECT memory version is unavailable");
        }
        return resolveCurrentProjectVersion(userId, memory.getProjectId());
    }

    private String resolveCurrentProjectVersion(Long userId, Long projectId) {
        String version = projectService.manifest(userId, projectId).version();
        if (!isProjectVersion(version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project version is unavailable");
        }
        return version;
    }

    private boolean isProjectVersion(String value) {
        return StringUtils.hasText(value) && PROJECT_VERSION_PATTERN.matcher(value).matches();
    }

    private String provenanceRef(Long memoryId, String action) {
        if (memoryId == null) {
            throw new IllegalStateException("memory id is required for provenance");
        }
        return "memory-settings:" + memoryId + ":" + action;
    }

    private String distilledFingerprint(Long userId, String scope, Long projectId,
                                        String memoryType, String content) {
        String canonical = userId + "\u0000" + scope + "\u0000" + projectId + "\u0000" + memoryType
                + "\u0000" + content.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private LongTermMemoryResponse toResponse(AgentLongTermMemory memory) {
        return new LongTermMemoryResponse(
                memory.getId(),
                memory.getUserId(),
                memory.getProjectId(),
                memory.getScope(),
                memory.getMemoryType(),
                memory.getContent(),
                readTags(memory.getTagsJson()),
                memory.getSourceType(),
                memory.getSourceRefId(),
                memory.getConfidence(),
                memory.getStatus(),
                memory.getConfirmationStatus(),
                memory.getConfirmedAt(),
                memory.getConfirmedSource(),
                memory.getProvenanceType(),
                memory.getProvenanceRef(),
                memory.getProjectVersion(),
                memory.getExpiresAt(),
                memory.getInvalidatedAt(),
                memory.getInvalidationReason(),
                memory.getSupersedesMemoryId(),
                memory.getSupersededByMemoryId(),
                memory.getCreatedAt(),
                memory.getUpdatedAt(),
                memory.getDeletedAt()
        );
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : AgentLongTermMemory.STATUS_ACTIVE;
    }

    private int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String normalizeScope(String scope) {
        String normalized = StringUtils.hasText(scope)
                ? scope.trim().toUpperCase(Locale.ROOT) : AgentLongTermMemory.SCOPE_USER;
        if (!AgentLongTermMemory.SCOPE_USER.equals(normalized)
                && !AgentLongTermMemory.SCOPE_PROJECT.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be USER or PROJECT");
        }
        return normalized;
    }

    private String normalizeMemoryType(String memoryType) {
        String normalized = StringUtils.hasText(memoryType)
                ? memoryType.trim().toUpperCase(Locale.ROOT) : AgentLongTermMemory.TYPE_FACT;
        if (!SAFE_MEMORY_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported memoryType");
        }
        return normalized;
    }

    private String writeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        if (tags.size() > MAX_TAGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many memory tags");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memory tags must not be blank");
            }
            String value = tag.trim();
            if (value.length() > MAX_TAG_CHARACTERS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memory tag is too long");
            }
            validateSafeText(value, "memory tag");
            normalized.add(value);
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize memory tags.", ex);
        }
    }

    private void validateSafeText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (ABSOLUTE_PATH_PATTERN.matcher(value).find() || SENSITIVE_PATTERN.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must not contain credentials or absolute local paths");
        }
    }

    private List<String> readTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse memory tags.", ex);
        }
    }

    record DistilledMemoryWriteResult(AgentLongTermMemory memory, boolean created) { }
}
