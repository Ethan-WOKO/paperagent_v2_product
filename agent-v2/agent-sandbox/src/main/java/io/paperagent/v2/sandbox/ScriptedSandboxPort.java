package io.paperagent.v2.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A deterministic, thread-safe test Sandbox backed by an immutable ordered
 * script. It performs no execution or other side effect.
 */
public final class ScriptedSandboxPort implements SandboxPort {
    private final List<ScriptStep> script;
    private final Object monitor = new Object();
    private final List<ScriptObservation> observations = new ArrayList<>();
    private int nextIndex;
    private long nextAttemptSequence;

    public ScriptedSandboxPort(List<ScriptStep> script) {
        this.script = SandboxValues.list(script, "scriptedSandboxPort.script");
        if (this.script.isEmpty()) {
            SandboxValues.fail(
                    SandboxValidationCode.INVALID_SCRIPT,
                    "scriptedSandboxPort.script",
                    "script must contain at least one step");
        }
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        SandboxValues.required(request, "scriptedSandboxPort.request");
        synchronized (monitor) {
            long attemptSequence = nextAttemptSequence++;
            int observedIndex = nextIndex;
            if (request.cancellationRequested()) {
                SandboxFailure failure = failure(
                        SandboxFailureCode.CANCELLED,
                        "request was cancelled before Sandbox execution",
                        Map.of("requestId", request.requestId().value()));
                observe(attemptSequence, observedIndex, request, false, failure.code());
                return failure;
            }
            if (nextIndex >= script.size()) {
                SandboxFailure failure = failure(
                        SandboxFailureCode.SCRIPTED_EXHAUSTED,
                        "script has no remaining entries",
                        Map.of("requestId", request.requestId().value()));
                observe(attemptSequence, observedIndex, request, false, failure.code());
                return failure;
            }

            ScriptStep expected = script.get(nextIndex);
            if (!expected.expectedRequest().equals(request)) {
                SandboxFailureCode code = matchesFutureStep(request)
                        ? SandboxFailureCode.SCRIPTED_OUT_OF_ORDER
                        : SandboxFailureCode.SCRIPTED_MISMATCH;
                SandboxFailure failure = failure(
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

            SandboxResult outcome = expected.outcome();
            nextIndex++;
            observe(
                    attemptSequence,
                    observedIndex,
                    request,
                    true,
                    outcome instanceof SandboxFailure failure
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
                throw new ScriptedSandboxAssertionException(failure(
                        SandboxFailureCode.SCRIPTED_UNCONSUMED,
                        "script contains unconsumed entries",
                        Map.of("remainingCount", Integer.toString(script.size() - nextIndex))));
            }
        }
    }

    private boolean matchesFutureStep(SandboxRequest request) {
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
            SandboxRequest request,
            boolean consumed,
            SandboxFailureCode failureCode) {
        observations.add(new ScriptObservation(
                attemptSequence,
                scriptIndex,
                request,
                consumed,
                Optional.ofNullable(failureCode)));
    }

    private static SandboxFailure failure(
            SandboxFailureCode code,
            String message,
            Map<String, String> details) {
        return new SandboxFailure(code, message, details);
    }

    public record ScriptStep(SandboxRequest expectedRequest, SandboxResult outcome) {
        public ScriptStep {
            SandboxValues.required(expectedRequest, "scriptStep.expectedRequest");
            SandboxValues.required(outcome, "scriptStep.outcome");
            if (outcome instanceof ExecutedCommand executed) {
                SandboxProtocolValidator.validate(expectedRequest, executed);
            }
        }
    }
}
