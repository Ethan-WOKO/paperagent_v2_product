package com.yanban.api.agent.v2.synthesis;

import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.FinalSynthesisRepository;
import io.paperagent.v2.persistence.ReceiptRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisCompositionResult;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisDisposition;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrator;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisReceiptSource;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisStore;
import io.paperagent.v2.runtime.synthesis.ExactIntentOwnershipSource;
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
        FinalSynthesisStore synthesisStore = new FinalSynthesisStore() {
            @Override
            public java.util.Optional<io.paperagent.v2.contracts.FinalSynthesis> find(
                    io.paperagent.v2.contracts.PlanId planId) {
                var result = syntheses.find(planId);
                return result.outcome() == PersistenceOutcome.FOUND
                        ? result.value()
                        : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<FinalSynthesisCompositionResult> append(
                    io.paperagent.v2.contracts.FinalSynthesis value) {
                var result = syntheses.append(value);
                if (result.outcome() != PersistenceOutcome.APPLIED
                        && result.outcome() != PersistenceOutcome.REPLAYED) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(
                        new FinalSynthesisCompositionResult(
                                result.value().orElseThrow(),
                                result.outcome() == PersistenceOutcome.APPLIED
                                        ? FinalSynthesisDisposition.APPLIED
                                        : FinalSynthesisDisposition.REPLAYED));
            }
        };
        FinalSynthesisReceiptSource receiptSource = receiptId -> {
            var result = receipts.find(receiptId);
            return result.outcome() == PersistenceOutcome.FOUND
                    ? result.value()
                    : java.util.Optional.empty();
        };
        ExactIntentOwnershipSource intentSource =
                (toolCallId, planId, stepId) -> {
                    var result = intents.find(toolCallId);
                    return result.outcome() == PersistenceOutcome.FOUND
                            && result.value().orElseThrow().intent().planId()
                            .equals(planId)
                            && result.value().orElseThrow().intent().stepId()
                            .equals(stepId)
                            && result.value().orElseThrow().intent().kind()
                            .equals("literature.search");
                };
        return new DefaultFinalSynthesisComposer(
                synthesisStore, receiptSource, intentSource, narrator);
    }
}
