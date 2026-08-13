package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.ReceiptStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure mechanical failure and no-progress views; it makes no policy decision. */
final class ProductCurrentStepActionAnalysisValues {
    private ProductCurrentStepActionAnalysisValues() {
    }

    static List<ProductCurrentStepActionFacts.ActionView> unresolvedFailures(
            List<ProductCurrentStepActionFacts.ActionView> actions) {
        int after = 0;
        for (int index = 0; index < actions.size(); index++) {
            var result = actions.get(index).result();
            if (actions.get(index).candidate() != null
                    || (actions.get(index).candidateFailure() == null
                    && result != null
                    && result.receipt().status() == ReceiptStatus.SUCCESS)) {
                after = index + 1;
            }
        }
        List<ProductCurrentStepActionFacts.ActionView> failures =
                new ArrayList<>();
        for (int index = after; index < actions.size(); index++) {
            var action = actions.get(index);
            if (action.candidateFailure() != null
                    || (action.result() != null
                    && action.result().receipt().status()
                    != ReceiptStatus.SUCCESS)) failures.add(action);
        }
        return List.copyOf(failures);
    }

    static List<ProductCurrentStepActionFacts.ActionView> keyViews(
            List<ProductCurrentStepActionFacts.ActionView> actions,
            List<ProductCurrentStepActionFacts.ActionView> failures) {
        if (actions.isEmpty()) return List.of();
        List<ProductCurrentStepActionFacts.ActionView> result =
                new ArrayList<>(failures);
        var latest = actions.get(actions.size() - 1);
        if (!result.contains(latest)) result.add(latest);
        return List.copyOf(result);
    }

    static ChainContextValue diagnosis(
            List<ProductCurrentStepActionFacts.ActionView> actions) {
        List<ChainContextValue> values = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            if (action.candidateFailure() != null) {
                values.add(ChainContextValue.object(Map.of(
                        "actionRef", ref(action.binding().actionId()),
                        "failureRef", ref(action.candidateFailure()
                                .candidateFailureId()),
                        "errorCode", ChainContextValue.text(
                                action.candidateFailure().errorCode()),
                        "laterAttemptExists", ChainContextValue.bool(
                                index + 1 < actions.size()))));
                continue;
            }
            if (action.result() != null
                    && action.result().receipt().status()
                    != ReceiptStatus.SUCCESS) {
                values.add(ChainContextValue.object(Map.of(
                        "actionRef", ref(action.binding().actionId()),
                        "receiptRef", ref(action.result().receipt().id().value()),
                        "resultCode", action.result().receipt().resultCode()
                                .<ChainContextValue>map(ChainContextValue::text)
                                .orElseGet(ChainContextValue::nil),
                        "laterAttemptExists", ChainContextValue.bool(
                                index + 1 < actions.size()))));
            }
        }
        return ChainContextValue.object(Map.of(
                "kind", ChainContextValue.text(
                        "MECHANICAL_RECEIPT_DIAGNOSIS"),
                "failedAttempts", ChainContextValue.array(values)));
    }

    static ChainContextValue noProgress(
            List<ProductCurrentStepActionFacts.ActionView> actions) {
        if (actions.isEmpty()) return ChainContextValue.object(Map.of(
                "latestActionRef", ChainContextValue.nil(),
                "latestSignatureOccurrences", ChainContextValue.number(0),
                "consecutiveIdenticalTerminalOutcomes",
                ChainContextValue.number(0)));
        var latest = actions.get(actions.size() - 1);
        long signatures = actions.stream().filter(value ->
                value.binding().actionSignatureSha256().equals(
                        latest.binding().actionSignatureSha256())).count();
        int identical = 0;
        String outcome = terminalIdentity(latest);
        if (outcome != null) {
            for (int index = actions.size() - 1; index >= 0
                    && outcome.equals(terminalIdentity(actions.get(index)));
                    index--) identical++;
        }
        return ChainContextValue.object(Map.of(
                "latestActionRef", ref(latest.binding().actionId()),
                "latestSignatureOccurrences", ChainContextValue.number(
                        signatures),
                "consecutiveIdenticalTerminalOutcomes",
                ChainContextValue.number(identical)));
    }

    private static String terminalIdentity(
            ProductCurrentStepActionFacts.ActionView action) {
        if (action.candidateFailure() != null) {
            return action.binding().actionSignatureSha256() + "\0"
                    + action.candidateFailure().mutationAuthorityType() + "\0"
                    + action.candidateFailure().errorCode();
        }
        if (action.candidate() != null) {
            return action.binding().actionSignatureSha256() + "\0"
                    + "CANDIDATE_COMMITTED";
        }
        if (action.result() == null) return null;
        var receipt = action.result().receipt();
        return action.binding().actionSignatureSha256() + "\0"
                + receipt.status().name() + "\0"
                + receipt.resultCode().orElse("NONE") + "\0"
                + receipt.exitCode().map(String::valueOf).orElse("NONE");
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
