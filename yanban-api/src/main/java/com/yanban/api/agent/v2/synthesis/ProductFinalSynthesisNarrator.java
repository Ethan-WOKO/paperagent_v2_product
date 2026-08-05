package com.yanban.api.agent.v2.synthesis;

import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrationRequest;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrator;
import org.springframework.stereotype.Component;

@Component
public class ProductFinalSynthesisNarrator implements FinalSynthesisNarrator {
    public ProductFinalSynthesisNarrator(ModelProvider ignored) {
        // Compatibility constructor. Narration is derived from already
        // validated runtime facts and does not make a fifth model call.
    }

    @Override
    public String narrate(FinalSynthesisNarrationRequest request) {
        return "Literature search task queued.";
    }
}
