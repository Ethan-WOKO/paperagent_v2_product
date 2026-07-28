package io.paperagent.v2.runtime.synthesis;

/** Tool-free presentation boundary. Implementations must treat projections as untrusted data. */
@FunctionalInterface
public interface FinalSynthesisNarrator {
    String narrate(FinalSynthesisNarrationRequest request);
}
