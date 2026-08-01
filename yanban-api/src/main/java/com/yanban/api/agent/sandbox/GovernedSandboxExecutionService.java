package com.yanban.api.agent.sandbox;

import com.yanban.api.agent.SandboxPlanAuthorityResolver;
import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.sandbox.contract.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Transitional internal boundary; durable outbox service is the only intended caller. */
@Service
@ConditionalOnProperty(prefix="yanban.sandbox",name="enabled",havingValue="true")
final class GovernedSandboxExecutionService {
    private final SandboxExecutionProperties properties;
    private final SandboxCommandPolicy commands;
    private final ProjectService projects;
    GovernedSandboxExecutionService(SandboxExecutionProperties properties,
                                    SandboxCommandPolicy commands,ProjectService projects){this.properties=properties;this.commands=commands;this.projects=projects;}

    SandboxDispatch prepare(SandboxPlanAuthorityResolver.Resolution authority,Request request){
        return prepare(authority, request, null);
    }

    SandboxDispatch prepare(SandboxPlanAuthorityResolver.Resolution authority, Request request,
                            CandidateArtifactResponse candidate) {
        if(!properties.isEnabled())fail(SandboxFailureCode.SANDBOX_DISABLED,"sandbox disabled");
        if(authority==null||request==null||authority.remainingExecutions()<1)fail(SandboxFailureCode.AUTHORITY_REJECTED,"authority rejected");
        commands.validate(request.argv(),Map.of());
        ProjectManifestResponse manifest = projects.manifest(authority.userId(), authority.projectId());
        String version = manifest.version();
        if(!version.equals(authority.projectVersion()))fail(SandboxFailureCode.STALE_PROJECT_VERSION,"ProjectVersion stale");
        List<String> knownPaths = new ArrayList<>();
        manifest.files().stream().map(ProjectFileEntry::path).forEach(knownPaths::add);
        if (candidate != null) {
            candidate.changes().stream()
                    .filter(change -> change.type() != CandidateFileChange.Type.DELETE)
                    .map(change -> change.relativePath().value())
                    .forEach(knownPaths::add);
        }
        ProjectMaterialScope.CanonicalPathResolution resolution =
                ProjectMaterialScope.resolveCanonicalPaths(request.relativePaths(), knownPaths);
        if (!resolution.valid()) {
            throw pathFailure("sandbox target path could not be resolved", request.relativePaths(), resolution);
        }
        Set<String> resolvedPaths = resolution.paths();
        List<String> resolvedArgv = request.argv().stream()
                .map(resolution::canonicalAlias)
                .toList();
        commands.validate(resolvedArgv, Map.of());
        Map<String, String> files;
        try {
            files = candidate == null
                    ? materialize(authority, resolvedPaths).textFiles()
                    : materializeCandidate(authority, resolvedPaths, manifest, candidate);
        } catch (SandboxExecutionException exception) {
            throw exception.withPathDiagnostic(
                    "MATERIALIZE", request.relativePaths(), resolvedPaths,
                    resolution.missingPaths(), resolution.ambiguities());
        }
        if(files.size()!=resolvedPaths.size()) {
            LinkedHashSet<String> missingAfterMaterialization = new LinkedHashSet<>(resolvedPaths);
            missingAfterMaterialization.removeAll(files.keySet());
            throw pathFailure(
                    "requested file missing", request.relativePaths(), resolution, missingAfterMaterialization);
        }
        SandboxDispatch unsigned=new SandboxDispatch(request.idempotencyKey(),"",authority.userId(),authority.projectId(),
                authority.sessionId(),authority.planId(),authority.stepId(),authority.lease().fence(),version,
                authority.policyDigest(),files,resolvedArgv,properties.getCpus(),properties.getMemoryLimit().toBytes(),
                properties.getExecutionTimeout().toMillis(),properties.getMaxOutputSize().toBytes(),false);
        SandboxDispatch dispatch=new SandboxDispatch(unsigned.idempotencyKey(),SandboxCanonicalDigest.compute(unsigned),unsigned.userId(),
                unsigned.projectId(),unsigned.sessionId(),unsigned.planId(),unsigned.stepId(),unsigned.fence(),unsigned.projectVersion(),
                unsigned.policyDigest(),unsigned.files(),unsigned.argv(),unsigned.cpus(),unsigned.memoryBytes(),unsigned.timeoutMillis(),unsigned.maxOutputBytes(),false);
        return dispatch;
    }

    private ProjectService.SandboxWorkspaceMaterialization materialize(
            SandboxPlanAuthorityResolver.Resolution authority, Set<String> paths) {
        try {
            return projects.materializeSandbox(authority.userId(), authority.projectId(), paths);
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            if(ex.getStatusCode().value()==409)fail(SandboxFailureCode.STALE_PROJECT_VERSION,
                    "Project changed during sandbox materialization");
            if(ex.getStatusCode().value()==404)fail(SandboxFailureCode.INVALID_PATH,
                    "requested Project file was not found");
            fail(SandboxFailureCode.AUTHORITY_REJECTED,"Project materialization was rejected");
            throw ex;
        }
    }

    private Map<String, String> materializeCandidate(SandboxPlanAuthorityResolver.Resolution authority,
                                                      Set<String> paths,
                                                      ProjectManifestResponse manifest,
                                                      CandidateArtifactResponse candidate) {
        if (candidate.projectId() != authority.projectId()
                || !candidate.projectVersion().value().equals(authority.projectVersion())
                || candidate.governanceStatus() != CandidateChangeSet.GovernanceStatus.VALIDATED
                || !candidate.validation().valid()
                || candidate.applicationStatus() != CandidateChangeSet.ApplicationStatus.NOT_APPLIED) {
            fail(SandboxFailureCode.AUTHORITY_REJECTED, "Candidate is not current and validated");
        }
        Map<String, CandidateFileChange> changes = new LinkedHashMap<>();
        candidate.changes().forEach(change -> changes.put(change.relativePath().value(), change));
        if (paths.stream().noneMatch(changes::containsKey)) {
            fail(SandboxFailureCode.INVALID_PATH, "sandbox target does not match the governed Candidate");
        }
        Set<String> basePaths = paths.stream()
                .filter(path -> {
                    CandidateFileChange change = changes.get(path);
                    return change == null || change.type() != CandidateFileChange.Type.ADD;
                })
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> files = new LinkedHashMap<>();
        if (!basePaths.isEmpty()) files.putAll(materialize(authority, basePaths).textFiles());
        Map<String, ProjectFileEntry> manifestFiles = new LinkedHashMap<>();
        manifest.files().forEach(file -> manifestFiles.put(file.path(), file));
        for (String path : paths) {
            CandidateFileChange change = changes.get(path);
            if (change == null) continue;
            ProjectFileEntry base = manifestFiles.get(path);
            if (change.type() == CandidateFileChange.Type.ADD) {
                if (base != null) fail(SandboxFailureCode.STALE_PROJECT_VERSION,
                        "Candidate ADD target now exists");
                files.put(path, change.candidateText().text());
            } else if (change.type() == CandidateFileChange.Type.MODIFY) {
                if (base == null || change.baseFileHash() == null
                        || !change.baseFileHash().sha256().equals(base.sha256())) {
                    fail(SandboxFailureCode.STALE_PROJECT_VERSION, "Candidate base file changed");
                }
                files.put(path, change.candidateText().text());
            } else {
                files.remove(path);
            }
        }
        return Map.copyOf(files);
    }
    private static SandboxExecutionException pathFailure(
            String message,
            Set<String> requestedPaths,
            ProjectMaterialScope.CanonicalPathResolution resolution) {
        return pathFailure(message, requestedPaths, resolution, resolution.missingPaths());
    }

    private static SandboxExecutionException pathFailure(
            String message,
            Set<String> requestedPaths,
            ProjectMaterialScope.CanonicalPathResolution resolution,
            Set<String> missingPaths) {
        return new SandboxExecutionException(
                SandboxFailureCode.INVALID_PATH,
                message + "; requestedPaths=" + requestedPaths
                        + "; resolvedPaths=" + resolution.paths()
                        + "; missingPaths=" + missingPaths
                        + "; ambiguities=" + resolution.ambiguities(),
                "MATERIALIZE",
                requestedPaths,
                resolution.paths(),
                missingPaths,
                resolution.ambiguities());
    }
    private static void fail(SandboxFailureCode code,String message){throw new SandboxExecutionException(code,message);}
    record Request(String idempotencyKey,Set<String> relativePaths,List<String> argv){Request{relativePaths=relativePaths==null?Set.of():Set.copyOf(relativePaths);argv=argv==null?List.of():List.copyOf(argv);}}
}
