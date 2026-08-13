package com.yanban.api.agent.v2.chain.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Restores a persisted ref-only proposal into its transient provider wire shape. */
final class ProductChainPersistedProposalDecoder {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductChainPersistedProposalDecoder() {
    }

    static ProviderRoleOutput decode(
            ChainModelProtocolOutcome.ProposalReady ready,
            ChainWorkState state,
            String gapId) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(state, "state");
        ChainPersistenceRecords.ModelProposalRecord proposal = ready.proposal();
        ChainPersistenceRecords.ContentRecord body = ready.bodyContent();
        try {
            if (!sha256(proposal.payload().json()).equals(
                    proposal.payload().sha256())) {
                throw failure("CHAIN_PROPOSAL_PAYLOAD_DIGEST_INVALID");
            }
            JsonNode canonical = JSON.readTree(proposal.payload().json());
            if (!(canonical instanceof ObjectNode payload)) {
                throw failure("CHAIN_PROPOSAL_PAYLOAD_NOT_OBJECT");
            }
            if (body == null) {
                if (proposal.bodyAuthorityType() != null
                        || proposal.bodyAuthorityRef() != null) {
                    throw failure("CHAIN_PROPOSAL_BODY_AUTHORITY_MISSING");
                }
            } else {
                BodyFields fields = bodyFields(body.contentKind());
                validateAuthority(proposal, body, fields);
                JsonNode ref = payload.remove(fields.refField());
                if (ref == null || !ref.isTextual()
                        || !body.contentId().equals(ref.textValue())
                        || payload.has(fields.inlineField())) {
                    throw failure("CHAIN_PROPOSAL_BODY_REF_MISMATCH");
                }
                payload.put(fields.inlineField(), body.body());
            }
            String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                    + proposal.proposalKind().wireName()
                    + "\",\"payload\":"
                    + JSON.writeValueAsString(payload) + "}";
            return new StrictChainProviderOutputParser().parse(
                    encoded, proposal.role(), state, gapId);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException(
                    "CHAIN_PROPOSAL_CANONICAL_PAYLOAD_INVALID", invalid);
        }
    }

    private static void validateAuthority(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ContentRecord body,
            BodyFields fields) {
        if (proposal.role() != fields.role()
                || !proposal.taskId().equals(body.taskId())
                || !proposal.invocationId().equals(body.invocationId())
                || !body.contentKind().name().equals(
                        proposal.bodyAuthorityType())
                || !body.contentId().equals(proposal.bodyAuthorityRef())
                || !sha256(body.body()).equals(body.bodySha256())) {
            throw failure("CHAIN_PROPOSAL_BODY_AUTHORITY_INVALID");
        }
    }

    private static BodyFields bodyFields(ChainContentKind kind) {
        return switch (kind) {
            case ANSWER_BODY -> new BodyFields(
                    ChainRole.ANSWER, "answerBodyRef", "inlineAnswerBody");
            case CANDIDATE_STEP_RESULT -> new BodyFields(
                    ChainRole.EXECUTOR, "candidateResultBodyRef",
                    "inlineCandidateResultBody");
            case WORKSPACE_CHANGE_BODY -> new BodyFields(
                    ChainRole.EXECUTOR, "workspaceChangeBodyRef",
                    "inlineCanonicalChangeBody");
            default -> throw failure("CHAIN_PROPOSAL_BODY_KIND_INVALID");
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte element : digest) {
                result.append(String.format("%02x", element & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private record BodyFields(
            ChainRole role, String refField, String inlineField) {
    }
}
