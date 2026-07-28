package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.FinalSynthesisRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductFinalSynthesisRepositoryAdapter
        implements FinalSynthesisRepository {
    private final ProductFinalSynthesisJpaRepository rows;
    private final ObjectMapper mapper;

    public ProductFinalSynthesisRepositoryAdapter(
            ProductFinalSynthesisJpaRepository rows, ObjectMapper mapper) {
        this.rows = rows;
        this.mapper = mapper;
    }

    @Override
    public synchronized PersistenceResult<FinalSynthesis> append(FinalSynthesis synthesis) {
        if (synthesis == null || synthesis.sourceProjectVersion().isPresent()
                || synthesis.workspaceDiff().isPresent()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "finalSynthesis");
        }
        Optional<ProductFinalSynthesisEntity> existing =
                rows.findById(synthesis.planId().value());
        if (existing.isPresent()) {
            return classify(existing.orElseThrow(), synthesis);
        }
        String receiptJson = receiptJson(synthesis.receiptIds());
        String canonical = canonical(synthesis, receiptJson);
        try {
            rows.saveAndFlush(new ProductFinalSynthesisEntity(
                    synthesis.planId().value(), synthesis.id().value(),
                    synthesis.taskFrameId().value(),
                    synthesis.planRevisionId().value(), receiptJson,
                    synthesis.narrative(), synthesis.observedAt(),
                    sha256(canonical), Instant.now()));
            return PersistenceResult.applied(synthesis);
        } catch (DataIntegrityViolationException race) {
            return rows.findById(synthesis.planId().value())
                    .map(row -> classify(row, synthesis))
                    .orElseThrow(() -> race);
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<FinalSynthesis> find(PlanId planId) {
        if (planId == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        }
        return rows.findById(planId.value())
                .map(this::decode)
                .map(PersistenceResult::found)
                .orElseGet(() -> PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND, "planId"));
    }

    private PersistenceResult<FinalSynthesis> classify(
            ProductFinalSynthesisEntity row, FinalSynthesis candidate) {
        FinalSynthesis existing = decode(row);
        return existing.equals(candidate)
                ? PersistenceResult.replayed(existing)
                : PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "finalSynthesis.planId");
    }

    private FinalSynthesis decode(ProductFinalSynthesisEntity row) {
        List<ReceiptId> ids;
        try {
            List<String> values = mapper.readValue(
                    row.receiptIdsJson(), new TypeReference<>() {});
            ids = values.stream().map(ReceiptId::new).toList();
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("stored final synthesis is invalid");
        }
        FinalSynthesis value = new FinalSynthesis(
                new FinalSynthesisId(row.synthesisId()),
                new TaskFrameId(row.taskFrameId()),
                new PlanId(row.planId()),
                new PlanRevisionId(row.planRevisionId()),
                Optional.empty(), Optional.empty(), ids,
                row.narrative(), row.observedAt());
        String canonical = canonical(value, row.receiptIdsJson());
        if (!sha256(canonical).equals(row.canonicalSha256())) {
            throw new IllegalStateException("stored final synthesis integrity failure");
        }
        return value;
    }

    private String receiptJson(List<ReceiptId> ids) {
        try {
            return mapper.writeValueAsString(
                    ids.stream().map(ReceiptId::value).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("receipt ids are not encodable");
        }
    }

    private static String canonical(FinalSynthesis value, String receiptJson) {
        return value.id().value() + "\n" + value.taskFrameId().value()
                + "\n" + value.planId().value() + "\n"
                + value.planRevisionId().value() + "\n" + receiptJson
                + "\n" + value.narrative() + "\n" + value.observedAt();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
