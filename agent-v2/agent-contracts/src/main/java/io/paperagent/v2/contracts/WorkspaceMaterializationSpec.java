package io.paperagent.v2.contracts;

/**
 * Stable caller-provided identity for a Workspace materialization attempt.
 *
 * <p>This value describes intent only. It does not prove that the Workspace
 * exists, bind it to a Plan, or authorize execution.</p>
 */
public record WorkspaceMaterializationSpec(
        WorkspaceId workspaceId,
        ProjectVersionRef sourceProjectVersion,
        WorkspaceMaterializationLimits limits) {

    public WorkspaceMaterializationSpec {
        Contracts.required(workspaceId, "workspaceMaterializationSpec.workspaceId");
        Contracts.required(sourceProjectVersion, "workspaceMaterializationSpec.sourceProjectVersion");
        Contracts.required(limits, "workspaceMaterializationSpec.limits");
    }

    @Override
    public String toString() {
        return "WorkspaceMaterializationSpec["
                + "workspaceId=<provided>, "
                + "sourceProjectVersion=<provided>, "
                + "limits=<provided>]";
    }
}
