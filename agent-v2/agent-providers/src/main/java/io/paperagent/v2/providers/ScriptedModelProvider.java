package io.paperagent.v2.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A deterministic, thread-safe test provider backed by an immutable ordered
 * script.
 */
public final class ScriptedModelProvider implements ModelProvider {
    private final List<ScriptStep> script;
    private final Object monitor = new Object();
    private final List<ScriptObservation> observations = new ArrayList<>();
    private int nextIndex;
    private long nextAttemptSequence;

    public ScriptedModelProvider(List<ScriptStep> script) {
        this.script = ProviderValues.list(script, "scriptedModelProvider.script");
        if (this.script.isEmpty()) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_SCRIPT,
                    "scriptedModelProvider.script",
                    "script must contain at least one step");
        }
    }

    @Override
    public ModelProviderResult complete(ModelRequest request) {
        ProviderValues.required(request, "scriptedModelProvider.request");
        synchronized (monitor) {
            long attemptSequence = nextAttemptSequence++;
            int observedIndex = nextIndex;
            if (request.cancellationRequested()) {
                ProviderFailure failure = failure(
                        ProviderFailureCode.CANCELLED,
                        "request was cancelled before the provider turn",
                        Map.of("requestId", request.requestId().value()));
                observe(attemptSequence, observedIndex, request, false, failure.code());
                return failure;
            }
            if (nextIndex >= script.size()) {
                ProviderFailure failure = failure(
                        ProviderFailureCode.SCRIPTED_EXHAUSTED,
                        "script has no remaining entries",
                        Map.of("requestId", request.requestId().value()));
                observe(attemptSequence, observedIndex, request, false, failure.code());
                return failure;
            }

            ScriptStep expected = script.get(nextIndex);
            if (!expected.expectedRequest().equals(request)) {
                ProviderFailureCode code = matchesFutureStep(request)
                        ? ProviderFailureCode.SCRIPTED_OUT_OF_ORDER
                        : ProviderFailureCode.SCRIPTED_MISMATCH;
                ProviderFailure failure = failure(
                        code,
                        "request did not match the next scripted entry",
                        Map.of(
                                "expectedRequestId",
                                expected.expectedRequest().requestId().value(),
                                "actualRequestId",
                                request.requestId().value(),
                                "scriptIndex",
                                Integer.toString(nextIndex)));
                observe(attemptSequence, observedIndex, request, false, failure.code());
                return failure;
            }

            ModelProviderResult outcome = expected.outcome();
            nextIndex++;
            observe(
                    attemptSequence,
                    observedIndex,
                    request,
                    true,
                    outcome instanceof ProviderFailure failure
                            ? failure.code()
                            : null);
            return outcome;
        }
    }

    public List<ScriptObservation> observations() {
        synchronized (monitor) {
            return List.copyOf(observations);
        }
    }

    public int consumedCount() {
        synchronized (monitor) {
            return nextIndex;
        }
    }

    public int remainingCount() {
        synchronized (monitor) {
            return script.size() - nextIndex;
        }
    }

    public void assertFullyConsumed() {
        synchronized (monitor) {
            if (nextIndex != script.size()) {
                throw new ScriptedProviderAssertionException(failure(
                        ProviderFailureCode.SCRIPTED_UNCONSUMED,
                        "script contains unconsumed entries",
                        Map.of("remainingCount", Integer.toString(script.size() - nextIndex))));
            }
        }
    }

    private boolean matchesFutureStep(ModelRequest request) {
        for (int index = nextIndex + 1; index < script.size(); index++) {
            if (script.get(index).expectedRequest().equals(request)) {
                return true;
            }
        }
        return false;
    }

    private void observe(
            long attemptSequence,
            int scriptIndex,
            ModelRequest request,
            boolean consumed,
            ProviderFailureCode failureCode) {
        observations.add(new ScriptObservation(
                attemptSequence,
                scriptIndex,
                request,
                consumed,
                java.util.Optional.ofNullable(failureCode)));
    }

    private static ProviderFailure failure(
            ProviderFailureCode code,
            String message,
            Map<String, String> details) {
        return new ProviderFailure(code, message, details);
    }

    public record ScriptStep(ModelRequest expectedRequest, ModelProviderResult outcome) {
        public ScriptStep {
            ProviderValues.required(expectedRequest, "scriptStep.expectedRequest");
            ProviderValues.required(outcome, "scriptStep.outcome");
        }
    }
}
