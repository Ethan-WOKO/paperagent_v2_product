package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;

/**
 * Provider-verified fact for one Workspace materialization.
 *
 * <p>The fingerprint describes the validated source manifest at
 * materialization time. It does not authorize execution or prove that the
 * current Workspace contents remain pristine.</p>
 */
public record VerifiedWorkspaceMaterialization(
        WorkspaceMaterializationSpec spec,
        ContentHash sourceManifestFingerprint) {

    public VerifiedWorkspaceMaterialization {
        if (spec == null || sourceManifestFingerprint == null) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.REQUIRED_VALUE_MISSING,
                    "verifiedWorkspaceMaterialization");
        }
    }

    public WorkspaceRef workspace() {
        return new WorkspaceRef(spec.workspaceId(), spec.sourceProjectVersion());
    }

    @Override
    public String toString() {
        return "VerifiedWorkspaceMaterialization["
                + "spec=<provided>, "
                + "sourceManifestFingerprint=<provided>]";
    }
}
