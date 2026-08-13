package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import java.util.Objects;

public final class ChainContextException extends RuntimeException {
    public enum FailureDisposition {
        PROPAGATE,
        FORMAL_BUILD_BLOCK
    }

    private final ChainContextErrorCode code;
    private final ChainContextModule failedModule;
    private final FailureDisposition failureDisposition;

    public ChainContextException(ChainContextErrorCode code, String message) {
        this(code, null, FailureDisposition.PROPAGATE, message);
    }

    public ChainContextException(
            ChainContextErrorCode code,
            ChainContextModule failedModule,
            String message) {
        this(code, failedModule, FailureDisposition.PROPAGATE, message);
    }

    public ChainContextException(
            ChainContextErrorCode code,
            ChainContextModule failedModule,
            FailureDisposition failureDisposition,
            String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.failedModule = failedModule;
        this.failureDisposition = Objects.requireNonNull(
                failureDisposition, "failureDisposition");
    }

    public ChainContextErrorCode code() {
        return code;
    }

    public ChainContextModule failedModule() {
        return failedModule;
    }

    public FailureDisposition failureDisposition() {
        return failureDisposition;
    }
}
