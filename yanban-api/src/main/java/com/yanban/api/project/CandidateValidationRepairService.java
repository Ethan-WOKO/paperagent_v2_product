package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.compatibility.project.*;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Starts at most one persistent V2 repair Plan for a failed single-file Java validation. */
@Service
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
class CandidateValidationRepairService {
    private final CandidateSandboxValidationRepository validations;
    private final CandidateValidationRepairRepository repairs;
    private final CandidateChangeArtifactService candidates;
    private final CandidateSandboxValidationService validationService;
    private final V2ProjectCandidateService v2Candidates;
    private final ProjectCandidateEffectGateway candidateEffects;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    CandidateValidationRepairService(CandidateSandboxValidationRepository validations,
            CandidateValidationRepairRepository repairs, CandidateChangeArtifactService candidates,
            CandidateSandboxValidationService validationService, V2ProjectCandidateService v2Candidates,
            ProjectCandidateEffectGateway candidateEffects, ObjectMapper json,
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.validations = validations; this.repairs = repairs; this.candidates = candidates;
        this.validationService = validationService; this.v2Candidates = v2Candidates;
        this.candidateEffects = candidateEffects;
        this.json = json; this.jdbc = jdbc; this.transactions = transactions;
    }

    void repairAfterFailure(String validationId) {
        Authority authority = transactions.execute(status -> freeze(validationId));
        if (authority == null) return;
        try {
            var request = new V2ProjectCandidateRepairRequest(validationId, authority.artifactId(),
                    authority.fingerprint(), authority.selectedIndex(), authority.selectedPath(),
                    authority.receiptDigest(), authority.projectVersion(), 1, 1,
                    authority.replacements(), authority.stderr());
            V2ProjectCandidateResponse result = v2Candidates.executeRepair(authority.userId(),
                    authority.projectId(), authority.sessionId(), request);
            if (!result.terminal() || !"SUCCEEDED".equals(result.status())
                    || result.candidateArtifactId() == null) throw new IllegalStateException("repair Plan failed");
            CandidateArtifactResponse repaired = candidates.getCurrent(
                    authority.userId(), result.candidateArtifactId());
            var prepared = candidateEffects.requirePrepared(result.planId());
            List<String> coordinates = com.yanban.sandbox.contract.JavaMavenCoordinates
                    .normalize(prepared.mavenCoordinates());
            int index = selectedIndex(repaired, authority.selectedPath());
            CandidateValidationResponse validation = validationService.createRepair(
                    authority.userId(), authority.projectId(), repaired.artifactId(),
                    validationId, 1, authority.projectVersion(), index, coordinates);
            transactions.executeWithoutResult(status -> {
                CandidateValidationRepair row = requireFrozen(validationId, authority);
                row.completed(repaired.artifactId(), validation.validationId(),
                        write(coordinates), dbNow());
                repairs.saveAndFlush(row);
            });
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> repairs.findBySourceValidationId(validationId)
                    .ifPresent(value -> { value.rejected(dbNow()); repairs.saveAndFlush(value); }));
        }
    }

    private Authority freeze(String validationId) {
        CandidateSandboxValidation value = validations.lockByValidationId(validationId).orElse(null);
        if (value == null || !"FAILED".equals(value.status()) || value.repairOriginValidationId() != null
                || !CandidateValidationProfile.JAVA_SOURCE_RUN.name().equals(value.profile())
                || value.receiptJson() == null || value.receiptDigest() == null
                || !value.receiptDigest().equals(sha256(value.receiptJson()))) return null;
        SandboxReceipt receipt = readReceipt(value.receiptJson());
        if (receipt.exitCode() == null || receipt.exitCode() == 0) return null;
        List<Integer> indexes = readIndexes(value.acceptedChangeIndexesJson());
        if (indexes.size() != 1) return null;
        CandidateArtifactResponse candidate = candidates.getCurrent(value.userId(), value.artifactId());
        int selectedIndex = indexes.get(0);
        if (selectedIndex < 0 || selectedIndex >= candidate.changes().size()
                || !candidate.fingerprint().sha256().equals(value.candidateFingerprint())
                || candidate.projectId() != value.projectId()
                || !candidate.projectVersion().value().equals(value.projectVersion())
                || candidate.changes().size() > 4) throw new IllegalStateException("repair authority mismatch");
        Map<String, String> replacements = new LinkedHashMap<>();
        for (CandidateFileChange change : candidate.changes()) {
            if (change.type() != CandidateFileChange.Type.MODIFY || change.candidateText() == null
                    || replacements.putIfAbsent(change.relativePath().value(),
                    change.candidateText().text()) != null) {
                throw new IllegalStateException("repair requires unique MODIFY changes");
            }
        }
        String path = candidate.changes().get(selectedIndex).relativePath().value();
        if (!path.endsWith(".java")) return null;
        Authority authority = new Authority(value.userId(), value.projectId(), value.sessionId(),
                value.artifactId(), value.candidateFingerprint(), selectedIndex, path,
                value.receiptDigest(), value.projectVersion(), replacements,
                bounded(receipt.stderr(), 12_000));
        CandidateValidationRepair existing = repairs.findBySourceValidationId(validationId).orElse(null);
        if (existing != null) { requireFrozen(validationId, authority); return null; }
        try {
            repairs.saveAndFlush(new CandidateValidationRepair(validationId, value.artifactId(),
                    value.candidateFingerprint(), selectedIndex, path, value.receiptDigest(),
                    value.projectVersion(), replacements.get(path), sha256(replacements.get(path)), dbNow()));
            return authority;
        } catch (DataIntegrityViolationException race) { return null; }
    }

    private CandidateValidationRepair requireFrozen(String id, Authority expected) {
        CandidateValidationRepair row = repairs.findBySourceValidationId(id).orElseThrow();
        if (row.attempt() != 1 || row.maxAttempts() != 1
                || !row.sourceCandidateArtifactId().equals(expected.artifactId())
                || !row.sourceCandidateFingerprint().equals(expected.fingerprint())
                || !row.selectedChangeIndex().equals(expected.selectedIndex())
                || !row.selectedPath().equals(expected.selectedPath())
                || !row.failedReceiptDigest().equals(expected.receiptDigest())
                || !row.projectVersion().equals(expected.projectVersion())
                || !row.sourceReplacementText().equals(
                        expected.replacements().get(expected.selectedPath()))
                || !row.sourceReplacementDigest().equals(sha256(row.sourceReplacementText()))) {
            throw new IllegalStateException("Candidate repair authority mismatch");
        }
        return row;
    }

    private int selectedIndex(CandidateArtifactResponse candidate, String path) {
        for (int i = 0; i < candidate.changes().size(); i++) {
            if (path.equals(candidate.changes().get(i).relativePath().value())) return i;
        }
        throw new IllegalStateException("repaired path missing");
    }
    private SandboxReceipt readReceipt(String value) {
        try { return json.readValue(value, SandboxReceipt.class); }
        catch (Exception failure) { throw new IllegalStateException("invalid receipt"); }
    }
    private List<Integer> readIndexes(String value) {
        try { return json.readValue(value,
                json.getTypeFactory().constructCollectionType(List.class, Integer.class)); }
        catch (Exception failure) { throw new IllegalStateException("invalid selection"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp", LocalDateTime.class);
    }
    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String sha256(String value) {
        return CandidateSandboxValidationService.sha256(value);
    }
    private record Authority(long userId, long projectId, long sessionId, long artifactId,
            String fingerprint, int selectedIndex, String selectedPath, String receiptDigest,
            String projectVersion, Map<String, String> replacements, String stderr) {}
}
