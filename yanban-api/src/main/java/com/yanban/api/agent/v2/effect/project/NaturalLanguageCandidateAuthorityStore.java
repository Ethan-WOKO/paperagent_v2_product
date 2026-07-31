package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.compatibility.project.ProjectCandidateEffectAuthority;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable authority owned only by the natural-language Candidate path. */
@Component
public class NaturalLanguageCandidateAuthorityStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public NaturalLanguageCandidateAuthorityStore(
            JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ProjectCandidateEffectAuthority bind(
            Long userId, Long sessionId, Long turnId,
            String planId, String stepId, Long projectId,
            String projectVersion, String objective,
            String canonicalArguments, List<String> paths) {
        String pathsJson = write(paths);
        String digest = hash(canonicalArguments);
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    insert into agent_v2_natural_candidate_authorities
                    (user_id,session_id,turn_id,plan_id,step_id,project_id,
                     project_version,objective,paths_json,authority_json,
                     authority_sha256,status,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,'BOUND',?,?)
                    """,
                    userId, sessionId, turnId, planId, stepId, projectId,
                    projectVersion, objective, pathsJson,
                    canonicalArguments, digest, now, now);
        } catch (DuplicateKeyException replay) {
            // Verified below against every frozen field.
        }
        ProjectCandidateEffectAuthority value = require(planId, stepId);
        if (!Objects.equals(userId, value.userId())
                || !Objects.equals(sessionId, value.sessionId())
                || !Objects.equals(turnId, value.turnId())
                || !Objects.equals(projectId, value.projectId())
                || !projectVersion.equals(value.projectVersion())
                || !objective.equals(value.objective())
                || !paths.equals(value.paths())
                || !canonicalArguments.equals(value.authorityJson())
                || !digest.equals(value.authoritySha256())) {
            throw failed();
        }
        return value;
    }

    public ProjectCandidateEffectAuthority require(
            String planId, String stepId) {
        List<ProjectCandidateEffectAuthority> rows = query("""
                select user_id,session_id,turn_id,project_id,project_version,
                       objective,paths_json,authority_json,authority_sha256
                from agent_v2_natural_candidate_authorities
                where plan_id=? and step_id=?
                """, planId, stepId);
        if (rows.size() != 1) throw failed();
        return rows.get(0);
    }

    public ProjectCandidateEffectAuthority require(String planId) {
        return find(planId).orElseThrow(
                NaturalLanguageCandidateAuthorityStore::failed);
    }

    public Optional<ProjectCandidateEffectAuthority> find(String planId) {
        List<ProjectCandidateEffectAuthority> rows = query("""
                select user_id,session_id,turn_id,project_id,project_version,
                       objective,paths_json,authority_json,authority_sha256
                from agent_v2_natural_candidate_authorities
                where plan_id=?
                """, planId);
        return rows.size() == 1
                ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private List<ProjectCandidateEffectAuthority> query(
            String sql, Object... arguments) {
        return jdbc.query(sql, (ResultSet row, int ignored) ->
                        new ProjectCandidateEffectAuthority(
                                ProjectCandidateCompositionEffect.KIND,
                                row.getString("authority_json"),
                                row.getString("authority_sha256"),
                                row.getLong("user_id"),
                                row.getLong("project_id"),
                                row.getLong("session_id"),
                                row.getLong("turn_id"),
                                row.getString("project_version"),
                                row.getString("objective"),
                                readPaths(row.getString("paths_json"))),
                arguments);
    }

    @Transactional
    public void bindPrepared(
            String planId, Map<String, String> replacements,
            String diffFingerprint) {
        int changed = jdbc.update("""
                update agent_v2_natural_candidate_authorities
                set replacements_json=?, diff_fingerprint=?,
                    status='PREPARED', updated_at=?
                where plan_id=? and status='BOUND'
                """, write(new TreeMap<>(replacements)), diffFingerprint,
                Instant.now(), planId);
        if (changed != 1) {
            Prepared existing = requirePrepared(planId);
            if (!existing.replacements().equals(replacements)
                    || !existing.diffFingerprint()
                            .equals(diffFingerprint)) {
                throw failed();
            }
        }
    }

    public Prepared requirePrepared(String planId) {
        List<Prepared> rows = jdbc.query("""
                select replacements_json,diff_fingerprint
                from agent_v2_natural_candidate_authorities
                where plan_id=? and status in ('PREPARED','PUBLISHED')
                """, (row, ignored) -> new Prepared(
                readMap(row.getString("replacements_json")),
                row.getString("diff_fingerprint")), planId);
        if (rows.size() != 1) throw failed();
        return rows.get(0);
    }

    @Transactional
    public void bindCandidate(
            String planId, Long artifactId,
            String candidateFingerprint, String diffFingerprint) {
        int changed = jdbc.update("""
                update agent_v2_natural_candidate_authorities
                set candidate_artifact_id=?, candidate_fingerprint=?,
                    status='PUBLISHED', updated_at=?
                where plan_id=? and status='PREPARED'
                  and diff_fingerprint=?
                """, artifactId, candidateFingerprint, Instant.now(),
                planId, diffFingerprint);
        if (changed != 1) {
            List<Published> rows = jdbc.query("""
                    select candidate_artifact_id,candidate_fingerprint,
                           diff_fingerprint
                    from agent_v2_natural_candidate_authorities
                    where plan_id=? and status='PUBLISHED'
                    """, (row, ignored) -> new Published(
                            row.getLong("candidate_artifact_id"),
                            row.getString("candidate_fingerprint"),
                            row.getString("diff_fingerprint")),
                    planId);
            if (rows.size() != 1
                    || !Objects.equals(
                            rows.get(0).artifactId(), artifactId)
                    || !Objects.equals(
                            rows.get(0).candidateFingerprint(),
                            candidateFingerprint)
                    || !Objects.equals(
                            rows.get(0).diffFingerprint(),
                            diffFingerprint)) {
                throw failed();
            }
        }
    }

    public Optional<Long> candidateArtifactId(String planId) {
        List<Long> values = jdbc.queryForList("""
                select candidate_artifact_id
                from agent_v2_natural_candidate_authorities
                where plan_id=? and status='PUBLISHED'
                """, Long.class, planId);
        return values.size() == 1
                ? Optional.ofNullable(values.get(0)) : Optional.empty();
    }

    /** True once this Plan already owns prepared or published Candidate data. */
    public boolean hasPreparedCandidate(String planId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from agent_v2_natural_candidate_authorities
                where plan_id=? and status in ('PREPARED','PUBLISHED')
                """, Integer.class, planId);
        return count != null && count > 0;
    }

    private List<String> readPaths(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception failure) {
            throw failed();
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception failure) {
            throw failed();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw failed();
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static IllegalStateException failed() {
        return new IllegalStateException(
                "V2 natural Candidate authority failed");
    }

    public record Prepared(
            Map<String, String> replacements, String diffFingerprint) {
        public Prepared {
            replacements = Map.copyOf(replacements);
        }
    }

    private record Published(
            Long artifactId,
            String candidateFingerprint,
            String diffFingerprint) {
    }
}
