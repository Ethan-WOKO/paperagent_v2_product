package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainModelProtocolTest {
    private final StrictChainProviderOutputParser parser = new StrictChainProviderOutputParser();

    @Test
    void parsesAllTwentyFiveKindsIntoTheirExactTypedPayloads() {
        List<ChainProposalPayload> payloads = allPayloads();
        assertEquals(25, payloads.size());
        for (ChainProposalPayload expected : payloads) {
            ProviderRoleOutput actual = parser.parse(envelope(expected), expected.role(),
                    legalWorkState(expected.role()), null);
            assertEquals(expected.kind(), actual.payload().kind());
            assertEquals(expected.getClass(), actual.payload().getClass());
            assertEquals(expected, actual.payload());
        }
    }

    @Test
    void rejectsUnknownVersionCrossRoleAndProviderForgedRuntimeOrBodyRefs() {
        ChainProviderProtocolException version = assertThrows(ChainProviderProtocolException.class,
                () -> parser.parse("{\"schemaVersion\":\"2\",\"kind\":\"DIRECT_ROUTE\",\"payload\":{}}",
                        ChainRole.PLANNER, ChainWorkState.PLANNING, null));
        assertEquals(ChainProviderProtocolCode.UNSUPPORTED_SCHEMA_VERSION, version.code());

        assertEquals(ChainProviderProtocolCode.UNKNOWN_KIND, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(envelope(allPayloads().get(0)), ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, null)).code());

        String forgedIdentity = envelope(allPayloads().get(0)).replaceFirst("\\{",
                "{\"invocationId\":\"forged\",");
        assertEquals(ChainProviderProtocolCode.UNKNOWN_FIELD, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(forgedIdentity, ChainRole.PLANNER,
                        ChainWorkState.PLANNING, null)).code());

        String forgedBodyRef = envelope(new AnswerPayload.DirectAnswer(
                "route", "spec", "body", List.of())).replace(
                "\"inlineAnswerBody\":\"body\"",
                "\"answerBodyRef\":\"content.forged\"");
        assertEquals(ChainProviderProtocolCode.UNKNOWN_FIELD, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(forgedBodyRef, ChainRole.ANSWER,
                        ChainWorkState.DIRECT_ANSWERING, null)).code());
    }

    @Test
    void invalidJsonDiagnosticReportsOnlyOffsetAndNextCodePoint() {
        ChainProviderProtocolException failure = assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(
                        "{\"schemaVersion\":\"1\"，\"kind\":\"DIRECT_ROUTE\",\"payload\":{}}",
                        ChainRole.PLANNER, ChainWorkState.PLANNING, null));

        assertEquals(ChainProviderProtocolCode.INVALID_JSON, failure.code());
        assertTrue(failure.getMessage().contains("at offset 20 before U+FF0C"));
        assertTrue(failure.getMessage().length() < 96);
    }

    @Test
    void restoresOnlyMissingTerminalContainerDelimiters() {
        ChainProposalPayload expected = allPayloads().get(0);
        String complete = envelope(expected);
        String missingRootClose = complete.substring(0,
                complete.length() - 1);

        assertEquals(expected, parser.parse(missingRootClose,
                ChainRole.PLANNER, ChainWorkState.PLANNING, null).payload());
        String missingNestedCloses = complete.substring(0,
                complete.length() - 3);
        assertEquals(expected, parser.parse(missingNestedCloses,
                ChainRole.PLANNER, ChainWorkState.PLANNING, null).payload());

        String unterminatedString = complete.substring(0,
                complete.indexOf("\"schemaVersion\"") + 5);
        assertEquals(ChainProviderProtocolCode.INVALID_JSON, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(unterminatedString, ChainRole.PLANNER,
                        ChainWorkState.PLANNING, null)).code());
        String danglingComma = complete.substring(0,
                complete.length() - 1) + ",";
        assertEquals(ChainProviderProtocolCode.INVALID_JSON, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(danglingComma, ChainRole.PLANNER,
                        ChainWorkState.PLANNING, null)).code());
    }

    @Test
    void directRouteRequiresOneNonblankDeliverableAnswer() {
        PlannerPayload.DirectRoute valid = new PlannerPayload.DirectRoute(
                "plain knowledge question", "explain cross references",
                "LaTeX 交叉引用使用 label 和 ref。", List.of(), List.of(),
                false, false, false, false, null);
        assertEquals(valid, parser.parse(envelope(valid), ChainRole.PLANNER,
                ChainWorkState.PLANNING, null).payload());

        String empty = envelope(valid).replace(
                "LaTeX 交叉引用使用 label 和 ref。", "   ");
        assertEquals(ChainProviderProtocolCode.TYPE_MISMATCH,
                assertThrows(ChainProviderProtocolException.class,
                        () -> parser.parse(empty, ChainRole.PLANNER,
                                ChainWorkState.PLANNING, null)).code());
        assertEquals(ChainProviderProtocolCode.INVALID_JSON,
                assertThrows(ChainProviderProtocolException.class,
                        () -> parser.parse("{not-json", ChainRole.PLANNER,
                                ChainWorkState.PLANNING, null)).code());
    }

    @Test
    void validatesBoundGapAndStillPendingSuccessorWithoutChangingSchema() {
        GapValidation stillPending = new GapValidation("gap-1", List.of(
                new GapValidation.Check("need owner value", false, "answer-1")),
                GapValidation.Outcome.STILL_PENDING);
        PlannerPayload.NeedUserInput valid = new PlannerPayload.NeedUserInput(
                List.of("ownerValue"), "user-owned", "Which value?", "text",
                List.of("need owner value"), ChainRole.PLANNER, ChainRole.PLANNER,
                "planning", stillPending);
        assertEquals(valid, parser.parse(envelope(valid), ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM, "gap-1").payload());
        assertThrows(ChainProviderProtocolException.class, () -> parser.parse(
                envelope(valid), ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM, "other-gap"));

        PlannerPayload.DirectRoute invalidSuccessor = new PlannerPayload.DirectRoute(
                "reason", "spec", List.of(), List.of(), false, false, false, false,
                stillPending);
        assertThrows(ChainProviderProtocolException.class, () -> parser.parse(
                envelope(invalidSuccessor), ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM, "gap-1"));
    }

    @Test
    void persistentPlanRequiresTypedTaskRequirementsAndStepBindings() {
        ProposalFields.TaskFrameDraft taskFrame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"),
                List.of("constraint"), "version", "tier",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(new io.paperagent.v2.contracts.ValidationRequirement(
                                "validation-1",
                                io.paperagent.v2.contracts.ValidationSubject.ACTION_RECEIPT,
                                "receipt proves completion")),
                        io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        ProposalFields.PlanDraft plan = new ProposalFields.PlanDraft(List.of(
                new ProposalFields.StepDraft(
                        "step-1", 1, "objective", List.of(),
                        List.of("receipt proves completion"), List.of("scope"),
                        List.of("deliverable"), false, null, List.of(),
                        List.of("validation-1"))));
        PlannerPayload.PersistentPlan payload = new PlannerPayload.PersistentPlan(
                taskFrame, new ProposalFields.RoutingBoundary(
                true, false, true, false),
                List.of(new ProposalFields.RequirementCoverage(
                "deliverable", ProposalFields.RequirementStatus.PLANNED,
                List.of())), plan, List.of(), null);
        String valid = envelope(payload);

        assertEquals(payload, parser.parse(valid, ChainRole.PLANNER,
                ChainWorkState.PLANNING, null).payload());
        String missingRoutingBoundary = valid.replace(
                ",\"routingBoundary\":" + json(payload.routingBoundary()), "");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                valid, missingRoutingBoundary);
        assertEquals(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                assertThrows(ChainProviderProtocolException.class,
                        () -> parser.parse(missingRoutingBoundary,
                                ChainRole.PLANNER, ChainWorkState.PLANNING,
                                null)).code());
        String missingRequirements = valid.replace(
                ",\"requirements\":" + json(taskFrame.requirements()), "");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                valid, missingRequirements);
        assertEquals(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(missingRequirements, ChainRole.PLANNER,
                        ChainWorkState.PLANNING, null)).code());
        String missingBindingField = valid.replace(
                ",\"validationRequirementIds\":[\"validation-1\"]", "");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                valid, missingBindingField);
        assertEquals(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(missingBindingField, ChainRole.PLANNER,
                        ChainWorkState.PLANNING, null)).code());
    }

    @Test
    void acceptsOnlyCanonicalTypedWorkspaceChangeBundles() {
        String baseline = "a".repeat(64);
        String body = "{\"changes\":["
                + "{\"expectedBaselineSha256\":\"NONE\",\"path\":\"new.txt\",\"text\":\"new\",\"type\":\"ADD\"},"
                + "{\"expectedBaselineSha256\":\"" + baseline + "\",\"path\":\"old.txt\",\"text\":\"changed\",\"type\":\"MODIFY\"},"
                + "{\"expectedBaselineSha256\":\"" + baseline + "\",\"path\":\"gone.txt\",\"type\":\"DELETE\"}]}";
        ExecutorPayload.WorkspaceChange valid = new ExecutorPayload.WorkspaceChange(
                "candidate", List.of("new.txt", "old.txt", "gone.txt"), body, "reason",
                List.of("condition"), List.of(), null);
        assertEquals(valid, parser.parse(envelope(valid), ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, null).payload());

        assertEquals(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(envelope(valid), ChainRole.EXECUTOR,
                        ChainWorkState.PLANNING, null)).code());

        assertInvalidWorkspaceBody(List.of("file"), "{\"replacements\":[]}");
        assertInvalidWorkspaceBody(List.of("file"), "{\"changes\":[],\"extra\":true}");
        assertInvalidWorkspaceBody(List.of("file"), "{\"changes\":[]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"file\",\"text\":\"x\",\"type\":\"COPY\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"" + baseline + "\",\"path\":\"file\",\"text\":\"x\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"file\",\"text\":\"x\",\"type\":\"MODIFY\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"A" + "0".repeat(63) + "\",\"path\":\"file\",\"type\":\"DELETE\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"file\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"" + baseline + "\",\"path\":\"file\",\"text\":\"x\",\"type\":\"DELETE\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"extra\":true,\"path\":\"file\",\"text\":\"x\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\" \",\"text\":\"x\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("A.txt", "a.TXT"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"A.txt\",\"text\":\"x\",\"type\":\"ADD\"},{\"expectedBaselineSha256\":\"NONE\",\"path\":\"a.TXT\",\"text\":\"y\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("b.txt", "a.txt"),
                "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"a.txt\",\"text\":\"x\",\"type\":\"ADD\"},{\"expectedBaselineSha256\":\"NONE\",\"path\":\"b.txt\",\"text\":\"y\",\"type\":\"ADD\"}]}");
        assertInvalidWorkspaceBody(List.of("file"),
                "{ \"changes\": [{\"expectedBaselineSha256\":\"NONE\",\"path\":\"file\",\"text\":\"x\",\"type\":\"ADD\"}] }");
        assertInvalidWorkspaceBody(List.of("file"), "[]");
    }

    private void assertInvalidWorkspaceBody(List<String> targetFiles, String body) {
        ExecutorPayload.WorkspaceChange invalid = new ExecutorPayload.WorkspaceChange(
                "candidate", targetFiles, body, "reason",
                List.of("condition"), List.of(), null);
        assertEquals(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED, assertThrows(
                ChainProviderProtocolException.class,
                () -> parser.parse(envelope(invalid), ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, null)).code());
    }

    private static List<ChainProposalPayload> allPayloads() {
        ProposalFields.RequirementCoverage coverage = new ProposalFields.RequirementCoverage(
                "requirement", ProposalFields.RequirementStatus.SATISFIED, List.of("fact"));
        ProposalFields.ApplicabilitySuggestion applicability = new ProposalFields.ApplicabilitySuggestion(
                "accepted", ChainApplicability.Outcome.APPLICABLE, "reason", "position");
        ProposalFields.ReviewCommon review = new ProposalFields.ReviewCommon(
                "scope", List.of("object"), "reason", List.of("fact"), List.of());
        ProposalFields.FinalizationAssessment finalization = new ProposalFields.FinalizationAssessment(
                List.of(coverage), bound("artifact"), bound("candidate"), bound("validation"),
                bound("publish"), List.of("fact"), List.of());
        ProposalFields.TaskFrameDraft taskFrame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("object"), List.of("deliverable"), List.of(), "version", "tier",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(), io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        ProposalFields.PlanDraft plan = new ProposalFields.PlanDraft(List.of(new ProposalFields.StepDraft(
                "step-1", 1, "objective", List.of(), List.of("done"), List.of("scope"),
                List.of("deliverable"), false, null)));
        ReflectorPayload.AcceptStep accepted = new ReflectorPayload.AcceptStep(
                review, "candidate-result", List.of(coverage), List.of("artifact"),
                "task-frame", "revision", "step", "candidate", List.of(applicability));
        return List.of(
                new PlannerPayload.DirectRoute("reason", "spec", List.of(), List.of(),
                        false, false, false, false, null),
                new PlannerPayload.PersistentPlan(taskFrame, List.of(coverage), plan, List.of(), null),
                new PlannerPayload.PlanRevision("trigger", "old", plan, List.of(coverage), List.of(),
                        List.of(), List.of(), List.of(), "task-frame", null),
                new PlannerPayload.NeedUserInput(List.of("field"), "reason", "question", "format",
                        List.of("condition"), ChainRole.PLANNER, ChainRole.PLANNER, "position", null),
                new PlannerPayload.NeedPermission("kind", "scope", "purpose", "alternative", "position", null),
                new PlannerPayload.UserInstructionDisposition("instruction", "class", "continue", false,
                        "position", false, List.of(applicability), List.of(), null),
                new PlannerPayload.PlanningBlocked("category", List.of("fact"), "reason", "condition", "gap", null),
                new ExecutorPayload.ToolAction("tool", "{}", "target", "purpose", List.of("output"),
                        "permission", List.of(), List.of(), null, null, null, null, null),
                new ExecutorPayload.WorkspaceChange("candidate", List.of("file"),
                        "{\"changes\":[{\"expectedBaselineSha256\":\""
                                + "a".repeat(64)
                                + "\",\"path\":\"file\",\"text\":\"content\",\"type\":\"MODIFY\"}]}",
                        "reason",
                        List.of("condition"), List.of(), null),
                new ExecutorPayload.StepResult(List.of(coverage), "body", List.of(), null,
                        List.of(), List.of(), List.of(), List.of(), null),
                new ExecutorPayload.StepBlocked("category", "error", List.of("action"), "no progress",
                        "review", List.of(), null, null, null),
                new ReflectorPayload.ContinueStep(review, List.of("condition"), List.of("gap"), "scope"),
                accepted,
                new ReflectorPayload.AcceptStepAndReadyToFinalize(review, accepted, finalization),
                new ReflectorPayload.ReplanRequired(review, "reason", List.of(), List.of("constraint")),
                new ReflectorPayload.NeedUserInput(review, List.of("field"), "reason", "question", "format",
                        List.of("condition"), ChainRole.PLANNER, "position"),
                new ReflectorPayload.NeedPermission(review, "kind", "scope", "purpose", "alternative",
                        ChainRole.PLANNER, "NEW_INTAKE"),
                new ReflectorPayload.ReadyToFinalize(review, finalization),
                new ReflectorPayload.TaskFailed(review, finalization, List.of("fact"), List.of("item"), "category"),
                new AnswerPayload.DirectAnswer("route", "spec", "body", List.of()),
                new AnswerPayload.EscalateToPersistent("route", "reason", List.of("tool"), List.of(), false),
                new AnswerPayload.UserQuestion("gap", "body"),
                new AnswerPayload.StatusOrFailure("status", "decision", "outcome", "body"),
                new AnswerPayload.FinalDelivery("outcome", List.of("artifact"), "validation", "publish", "body"),
                new AnswerPayload.DeliveryBlocked("reason", List.of("fact"), "retry"));
    }

    private static ProposalFields.AuthorityAssessment bound(String ref) {
        return new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.BOUND, ref, null);
    }

    private static ChainWorkState legalWorkState(ChainRole role) {
        return switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.DIRECT_ANSWERING;
        };
    }

    private static String envelope(ChainProposalPayload payload) {
        return "{\"schemaVersion\":\"1\",\"kind\":" + quote(payload.kind().wireName())
                + ",\"payload\":" + json(payload) + "}";
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Enum<?> enumValue) return quote(enumValue.name());
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            list.forEach(element -> values.add(json(element)));
            return "[" + String.join(",", values) + "]";
        }
        if (value.getClass().isRecord()) {
            List<RecordComponent> components = List.of(value.getClass().getRecordComponents()).stream()
                    .sorted(Comparator.comparing(RecordComponent::getName)).toList();
            List<String> fields = new ArrayList<>();
            for (RecordComponent component : components) {
                try {
                    fields.add(quote(component.getName()) + ":" + json(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Map<?, ?> map) {
            throw new IllegalArgumentException("map not expected in proposal fixture: " + map);
        }
        throw new IllegalArgumentException("unsupported fixture value");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
