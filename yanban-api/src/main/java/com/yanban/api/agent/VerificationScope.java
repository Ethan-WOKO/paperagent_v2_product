package com.yanban.api.agent;

import java.util.List;

/** Human-readable hard boundary for what the attached material does and does not verify. */
public record VerificationScope(List<String> verifies, List<String> limitations) {
    public VerificationScope {
        verifies = verifies == null ? List.of() : List.copyOf(verifies);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static VerificationScope standard() {
        return new VerificationScope(
                List.of(
                        "A provider receipt verifies that an execution occurred and records its provider status.",
                        "Captured stdout/stderr verify what this execution printed.",
                        "A current Project version/path/hash/range binding verifies the referenced bytes."
                ),
                List.of(
                        "stdout/stderr do not make claims printed by the program true and do not prove correctness for every input.",
                        "A Project hash does not by itself prove semantic correctness.",
                        "Model inference and free-form summaries cannot override a provider receipt."
                ));
    }
}
