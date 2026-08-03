package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolCode;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolException;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelStage;

import java.util.Locale;
import java.util.Optional;

/** Sanitized protocol failure at a collaborator boundary. */
public final class PersistentPlanAgentLoopException
        extends RuntimeException {
    private final String stage;
    private final SingleTurnStepKernelStage kernelStage;
    private final SingleTurnStepKernelProtocolCode kernelCode;
    private final String kernelPath;
    private final boolean stepContextGuardFailure;

    PersistentPlanAgentLoopException(String stage) {
        super("Persistent Plan Agent Loop failed at " + stage);
        this.stage = stage;
        this.kernelStage = null;
        this.kernelCode = null;
        this.kernelPath = null;
        this.stepContextGuardFailure = false;
    }

    PersistentPlanAgentLoopException(
            String stage,
            SingleTurnStepKernelProtocolException kernelFailure) {
        super("Persistent Plan Agent Loop failed at " + stage, kernelFailure);
        this.stage = stage;
        this.kernelStage = kernelFailure.stage();
        this.kernelCode = kernelFailure.code();
        this.kernelPath = kernelFailure.path();
        this.stepContextGuardFailure = causedByStepContext(kernelFailure);
    }

    PersistentPlanAgentLoopException(
            String stage, StepModelCallGuardException guardFailure) {
        super("Persistent Plan Agent Loop failed at " + stage, guardFailure);
        this.stage = stage;
        this.kernelStage = null;
        this.kernelCode = null;
        this.kernelPath = null;
        this.stepContextGuardFailure = causedByStepContext(guardFailure);
    }

    public String stage() {
        return stage;
    }

    public String diagnosticStage() {
        if (kernelStage == null || kernelCode == null) {
            return stage;
        }
        return stage
                + "." + kernelStage.name().toLowerCase(Locale.ROOT)
                + "." + kernelCode.name().toLowerCase(Locale.ROOT);
    }

    public Optional<SingleTurnStepKernelStage> kernelStage() {
        return Optional.ofNullable(kernelStage);
    }

    public Optional<SingleTurnStepKernelProtocolCode> kernelCode() {
        return Optional.ofNullable(kernelCode);
    }

    public Optional<String> kernelPath() {
        return Optional.ofNullable(kernelPath);
    }

    public boolean stepContextGuardFailure() {
        return stepContextGuardFailure;
    }

    private static boolean causedByStepContext(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof StepModelCallGuardException) return true;
            current = current.getCause();
        }
        return false;
    }
}
