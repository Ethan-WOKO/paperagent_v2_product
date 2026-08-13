package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure canonical values for one formal action/effect authority cut. */
final class ProductCurrentStepActionValueCodec {
    static final ProductCurrentStepActionValueCodec INSTANCE =
            new ProductCurrentStepActionValueCodec();

    private ProductCurrentStepActionValueCodec() {
    }

    ChainContextValue attempt(
            ProductCurrentStepActionFacts.ActionView view) {
        var action = view.binding();
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        values.put("attemptNo", ChainContextValue.number(action.attemptNo()));
        values.put("authorityEventSequence", ChainContextValue.number(
                view.authorityEventSequence()));
        values.put("actionRef", ref(action.actionId()));
        values.put("proposalRef", ref(action.proposalId()));
        values.put("actionSignatureDigest", ChainContextValue.text(
                action.actionSignatureSha256()));
        values.put("effectState", ChainContextValue.text(effectState(view)));
        values.put("effectIntent", view.intent() == null
                ? ChainContextValue.nil() : intent(view));
        values.put("progressCount", ChainContextValue.number(
                view.progress().size()));
        values.put("receipt", receiptSummary(view));
        values.put("candidate", candidate(view));
        values.put("candidateMaterializationFailure",
                candidateFailure(view));
        return ChainContextValue.object(values);
    }

    ChainContextValue expanded(
            ProductCurrentStepActionFacts.ActionView view) {
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        values.put("attempt", attempt(view));
        values.put("progress", ChainContextValue.array(view.progress().stream()
                .map(value -> ChainContextValue.object(Map.of(
                        "progressRef", ref(value.progress().id().value()),
                        "sequence", ChainContextValue.number(
                                value.progress().sequence()),
                        "details", contract(value.progress().details()))))
                .toList()));
        values.put("receipt", view.result() == null
                ? ChainContextValue.nil() : fullReceipt(view));
        values.put("candidate", candidate(view));
        values.put("candidateMaterializationFailure",
                candidateFailure(view));
        return ChainContextValue.object(values);
    }

    ChainContextValue failureSummary(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidateFailure() != null) {
            return ChainContextValue.object(Map.of(
                    "attemptNo", ChainContextValue.number(
                            view.binding().attemptNo()),
                    "actionRef", ref(view.binding().actionId()),
                    "failureRef", ref(view.candidateFailure()
                            .candidateFailureId()),
                    "failureKind", ChainContextValue.text(
                            "CANDIDATE_MATERIALIZATION_FAILURE"),
                    "errorCode", ChainContextValue.text(
                            view.candidateFailure().errorCode()),
                    "receiptRef", view.result() == null
                            ? ChainContextValue.nil()
                            : ref(view.result().receipt().id().value())));
        }
        var receipt = view.result().receipt();
        return ChainContextValue.object(Map.of(
                "attemptNo", ChainContextValue.number(
                        view.binding().attemptNo()),
                "actionRef", ref(view.binding().actionId()),
                "receiptRef", ref(receipt.id().value()),
                "status", ChainContextValue.text(receipt.status().name()),
                "resultCode", receipt.resultCode()
                        .<ChainContextValue>map(ChainContextValue::text)
                        .orElseGet(ChainContextValue::nil),
                "exitCode", receipt.exitCode()
                        .<ChainContextValue>map(ChainContextValue::number)
                        .orElseGet(ChainContextValue::nil),
                "receiptDigest", digest(fullReceipt(view))));
    }

    ChainContextValue taskOutcomeFailure(
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (outcome == null
                || outcome.outcomeType()
                != io.paperagent.v2.chain.ChainTaskOutcomeStatus.FAILED) {
            return ChainContextValue.nil();
        }
        return ChainContextValue.object(Map.of(
                "outcomeRef", ref(outcome.outcomeId()),
                "status", ChainContextValue.text(outcome.outcomeType().name()),
                "failureCategory", ChainContextValue.text(
                        outcome.failureCategory()),
                "failureCode", ChainContextValue.text(outcome.failureCode()),
                "incompleteItems", canonical(outcome.incompleteItems()),
                "limitations", canonical(outcome.limitations()),
                "risks", canonical(outcome.risks())));
    }

    ChainContextValue intentVersion(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.intent() == null) {
            return ChainContextValue.object(Map.of(
                    "actionRef", ref(view.binding().actionId()),
                    "intent", ChainContextValue.text("NONE")));
        }
        return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "intentRef", ref(view.intent().intent().toolCallId().value()),
                "kind", ChainContextValue.text(view.intent().intent().kind()),
                "argumentsDigest", digest(contract(
                        view.intent().intent().arguments())),
                "activationRef", ref(view.intent().activationEventId().value()),
                "fencingToken", ChainContextValue.number(
                        view.intent().fencingToken())));
    }

    ChainContextValue progressVersion(
            ProductCurrentStepActionFacts.ActionView view) {
        List<ChainContextValue> vector = view.progress().stream().map(value ->
                (ChainContextValue) ChainContextValue.object(Map.of(
                        "progressRef", ref(value.progress().id().value()),
                        "sequence", ChainContextValue.number(
                                value.progress().sequence()),
                        "detailsDigest", digest(contract(
                                value.progress().details()))))).toList();
        return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "head", ChainContextValue.number(view.progress().size()),
                "vectorDigest", digest(ChainContextValue.array(vector))));
    }

    ChainContextValue receiptVersion(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.result() == null) return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "receipt", ChainContextValue.text("NONE")));
        return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "receiptRef", ref(view.result().receipt().id().value()),
                "status", ChainContextValue.text(
                        view.result().receipt().status().name()),
                "receiptDigest", digest(fullReceipt(view))));
    }

    ChainContextValue candidateFailureVersion(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidateFailure() == null) {
            return ChainContextValue.object(Map.of(
                    "actionRef", ref(view.binding().actionId()),
                    "candidateMaterializationFailure",
                    ChainContextValue.text("NONE")));
        }
        return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "failureRef", ref(view.candidateFailure()
                        .candidateFailureId()),
                "errorCode", ChainContextValue.text(
                        view.candidateFailure().errorCode())));
    }

    ChainContextValue candidateVersion(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidate() == null) {
            return ChainContextValue.object(Map.of(
                    "actionRef", ref(view.binding().actionId()),
                    "candidate", ChainContextValue.text("NONE")));
        }
        return ChainContextValue.object(Map.of(
                "actionRef", ref(view.binding().actionId()),
                "candidateRef", ref(view.candidate().workspaceCandidateId()),
                "candidateFingerprint", ChainContextValue.text(
                        view.candidate().candidateFingerprint()),
                "diffDigest", ChainContextValue.text(
                        view.candidate().diffDigest())));
    }

    ChainContextValue canonical(ChainPersistenceRecords.CanonicalJson value) {
        return ChainContextValue.object(Map.of(
                "formatVersion", ChainContextValue.number(value.formatVersion()),
                "sha256", ChainContextValue.text(value.sha256()),
                "json", ChainContextValue.text(value.json())));
    }

    private ChainContextValue intent(
            ProductCurrentStepActionFacts.ActionView view) {
        var intent = view.intent().intent();
        return ChainContextValue.object(Map.of(
                "intentRef", ref(intent.toolCallId().value()),
                "toolKind", ChainContextValue.text(intent.kind()),
                "argumentsDigest", digest(contract(intent.arguments()))));
    }

    private ChainContextValue receiptSummary(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.result() == null) return ChainContextValue.nil();
        var receipt = view.result().receipt();
        return ChainContextValue.object(Map.of(
                "receiptRef", ref(receipt.id().value()),
                "status", ChainContextValue.text(receipt.status().name()),
                "resultCode", receipt.resultCode()
                        .<ChainContextValue>map(ChainContextValue::text)
                        .orElseGet(ChainContextValue::nil)));
    }

    private ChainContextValue candidateFailure(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidateFailure() == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.of(
                "failureRef", ref(view.candidateFailure()
                        .candidateFailureId()),
                "errorCode", ChainContextValue.text(
                        view.candidateFailure().errorCode()),
                "mutationAuthorityType", ChainContextValue.text(
                        view.candidateFailure().mutationAuthorityType()),
                "mutationAuthorityRef", ref(view.candidateFailure()
                        .mutationAuthorityRef())));
    }

    private ChainContextValue candidate(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidate() == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.of(
                "candidateRef", ref(view.candidate().workspaceCandidateId()),
                "candidateFingerprint", ChainContextValue.text(
                        view.candidate().candidateFingerprint()),
                "diffDigest", ChainContextValue.text(
                        view.candidate().diffDigest())));
    }

    private ChainContextValue fullReceipt(
            ProductCurrentStepActionFacts.ActionView view) {
        return ProductCurrentStepReceiptValueCodec.INSTANCE.full(view);
    }

    private ChainContextValue contract(ContractValue value) {
        if (value instanceof TextValue item) return ChainContextValue.text(
                item.value());
        if (value instanceof NumberValue item) return ChainContextValue.object(
                Map.of("contractType", ChainContextValue.text("NUMBER"),
                        "decimal", ChainContextValue.text(
                                item.value().toPlainString())));
        if (value instanceof BooleanValue item) return ChainContextValue.bool(
                item.value());
        if (value instanceof NullValue) return ChainContextValue.nil();
        if (value instanceof ListValue item) return ChainContextValue.array(
                item.values().stream().map(this::contract).toList());
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        ((ObjectValue) value).values().forEach(
                (key, item) -> values.put(key, contract(item)));
        return ChainContextValue.object(values);
    }

    private static String effectState(
            ProductCurrentStepActionFacts.ActionView view) {
        if (view.candidateFailure() != null) {
            return "CANDIDATE_MATERIALIZATION_FAILED";
        }
        if (view.candidate() != null) return "CANDIDATE_COMMITTED";
        if (view.intent() == null) return "NOT_PREPARED";
        if (view.result() == null) return "IN_FLIGHT";
        return view.result().receipt().status().name();
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainContextValue.Text digest(ChainContextValue value) {
        return ChainContextValue.text(ProductChainContractProjectionCodec.sha256(
                ProductChainContractProjectionCodec.canonicalJson(value)));
    }
}
