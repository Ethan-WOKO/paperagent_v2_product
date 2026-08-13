package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ExecutionReceipt;

import java.util.Map;

/** Pure exact Receipt projection, kept separate from action-table values. */
final class ProductCurrentStepReceiptValueCodec {
    static final ProductCurrentStepReceiptValueCodec INSTANCE =
            new ProductCurrentStepReceiptValueCodec();

    private ProductCurrentStepReceiptValueCodec() {
    }

    ChainContextValue full(ProductCurrentStepActionFacts.ActionView view) {
        return full(view.result().receipt());
    }

    ChainContextValue full(ExecutionReceipt receipt) {
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("receiptRef", ref(receipt.id().value())),
                Map.entry("status", ChainContextValue.text(
                        receipt.status().name())),
                Map.entry("startedAt", ChainContextValue.text(
                        receipt.startedAt().toString())),
                Map.entry("endedAt", ChainContextValue.text(
                        receipt.endedAt().toString())),
                Map.entry("exitCode", receipt.exitCode()
                        .<ChainContextValue>map(ChainContextValue::number)
                        .orElseGet(ChainContextValue::nil)),
                Map.entry("resultCode", receipt.resultCode()
                        .<ChainContextValue>map(ChainContextValue::text)
                        .orElseGet(ChainContextValue::nil)),
                Map.entry("stdout", output(receipt.standardOutput(),
                        receipt.id().value())),
                Map.entry("stderr", output(receipt.standardError(),
                        receipt.id().value())),
                Map.entry("artifactRefs", ChainContextValue.array(
                        receipt.artifactReferences().stream()
                                .map(value -> ref(value.value())).toList())),
                Map.entry("diffRef", receipt.resultingDiff()
                        .<ChainContextValue>map(value -> ref(value.value()))
                        .orElseGet(ChainContextValue::nil)),
                Map.entry("eventRefs", ChainContextValue.array(
                        receipt.eventReferences().stream()
                                .map(value -> ref(value.value())).toList()))));
    }

    private ChainContextValue output(OutputCapture output, String receiptRef) {
        if (output.inlineText().isPresent()) return ChainContextValue.object(
                Map.of("kind", ChainContextValue.text("INLINE"),
                        "body", ChainContextValue.referencedText(
                                output.inlineText().orElseThrow(), receiptRef),
                        "truncated", ChainContextValue.bool(
                                output.truncated())));
        if (output.artifactRef().isPresent()) return ChainContextValue.object(
                Map.of("kind", ChainContextValue.text("ARTIFACT"),
                        "artifactRef", ref(output.artifactRef()
                                .orElseThrow().value()),
                        "truncated", ChainContextValue.bool(false)));
        return ChainContextValue.object(Map.of(
                "kind", ChainContextValue.text("EMPTY"),
                "truncated", ChainContextValue.bool(false)));
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
