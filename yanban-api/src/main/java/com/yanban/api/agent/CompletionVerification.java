package com.yanban.api.agent;

import java.util.List;

/** Deterministic, display-safe decision made from runtime facts rather than model prose. */
public record CompletionVerification(
        CompletionStatus status,
        List<String> reasons,
        List<String> evidenceRefs,
        boolean repairable,
        int reflectionAttempts,
        DomainVerification domainVerification
) {
    public CompletionVerification {
        status = status == null ? CompletionStatus.FAILED : status;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        reflectionAttempts = Math.max(0, reflectionAttempts);
        domainVerification = domainVerification == null ? DomainVerification.notApplicable() : domainVerification;
    }

    /** Source-compatible bridge for callers before domain verification was attached. */
    public CompletionVerification(CompletionStatus status,
                                  List<String> reasons,
                                  List<String> evidenceRefs,
                                  boolean repairable,
                                  int reflectionAttempts) {
        this(status, reasons, evidenceRefs, repairable, reflectionAttempts, DomainVerification.notApplicable());
    }

    public CompletionVerification afterReflection() {
        return new CompletionVerification(status, reasons, evidenceRefs, false, reflectionAttempts + 1,
                domainVerification);
    }
}
