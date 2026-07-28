package com.yanban.api.agent.v2.synthesis;

import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.FinalSynthesisRepository;
import io.paperagent.v2.persistence.ReceiptRepository;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductFinalSynthesisConfiguration {
    @Bean
    DefaultFinalSynthesisComposer finalSynthesisComposer(
            FinalSynthesisRepository syntheses,
            ReceiptRepository receipts,
            EffectIntentRepository intents,
            FinalSynthesisNarrator narrator) {
        return new DefaultFinalSynthesisComposer(
                syntheses, receipts, intents, narrator);
    }
}
