package io.paperagent.v2.contracts;

import java.util.Optional;

public record OutputCapture(Optional<String> inlineText, Optional<ArtifactRef> artifactRef, boolean truncated) {
    public static final int MAX_INLINE_CHARACTERS = 8_192;

    public OutputCapture {
        inlineText = Contracts.required(inlineText, "outputCapture.inlineText");
        artifactRef = Contracts.required(artifactRef, "outputCapture.artifactRef");
        if (inlineText.isPresent() && artifactRef.isPresent()) {
            Contracts.fail(ViolationCode.INVALID_RECEIPT, "outputCapture",
                    "output must be inline or referenced, not both");
        }
        if (inlineText.filter(text -> text.length() > MAX_INLINE_CHARACTERS).isPresent()) {
            Contracts.fail(ViolationCode.OUTPUT_LIMIT_EXCEEDED, "outputCapture.inlineText",
                    "inline output exceeds the contract bound");
        }
        if (inlineText.isEmpty() && artifactRef.isEmpty() && truncated) {
            Contracts.fail(ViolationCode.INVALID_RECEIPT, "outputCapture.truncated",
                    "empty output cannot be marked truncated");
        }
    }

    public static OutputCapture empty() {
        return new OutputCapture(Optional.empty(), Optional.empty(), false);
    }

    public static OutputCapture inline(String text, boolean truncated) {
        return new OutputCapture(Optional.of(Contracts.required(text, "output")), Optional.empty(), truncated);
    }

    public static OutputCapture artifact(ArtifactRef artifactRef) {
        return new OutputCapture(Optional.empty(), Optional.of(artifactRef), false);
    }
}
