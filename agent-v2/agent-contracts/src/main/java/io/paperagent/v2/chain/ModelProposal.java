package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

/** Persistable ref-only proposal after Provider body conversion and runtime identity binding. */
public record ModelProposal(
        String schemaVersion,
        ChainIdentity.Proposal identity,
        ChainWorkState workState,
        List<String> sourceRefs,
        String bodyAuthorityType,
        String bodyAuthorityRef,
        String canonicalRefOnlyPayload) {
    public ModelProposal {
        schemaVersion = ChainValues.required(schemaVersion, "schemaVersion");
        if (!ProviderRoleOutput.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        identity = Objects.requireNonNull(identity, "identity");
        workState = Objects.requireNonNull(workState, "workState");
        sourceRefs = ChainValues.copy(sourceRefs, "sourceRefs");
        canonicalRefOnlyPayload = ChainValues.required(canonicalRefOnlyPayload, "canonicalRefOnlyPayload");
        if (!identity.payloadHash().equals(ChainValues.sha256(canonicalRefOnlyPayload))) {
            throw new IllegalArgumentException("canonical ref-only payload does not match the frozen payload hash");
        }
        if ((bodyAuthorityType == null) != (bodyAuthorityRef == null)) {
            throw new IllegalArgumentException("body authority type/ref must both be present or absent");
        }
        if (bodyAuthorityType != null) {
            bodyAuthorityType = ChainValues.required(bodyAuthorityType, "bodyAuthorityType");
            bodyAuthorityRef = ChainValues.required(bodyAuthorityRef, "bodyAuthorityRef");
        }
        if (!identity.sourceRefs().equals(sourceRefs)) {
            throw new IllegalArgumentException("proposal source refs must match the frozen identity");
        }
        if (!Objects.equals(identity.bodyRef(), bodyAuthorityRef)) {
            throw new IllegalArgumentException("proposal body ref must match the frozen identity");
        }
    }
}
