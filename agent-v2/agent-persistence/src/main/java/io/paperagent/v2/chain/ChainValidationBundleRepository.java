package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

/** Atomic persistence boundary for one plan-level Validation bundle. */
public interface ChainValidationBundleRepository {
    BundleAppendResult appendBundle(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ValidationBundleRecord> bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets);

    Optional<ChainPersistenceRecords.ValidationBundleRecord> findBundle(
            String validationBundleId);

    List<ChainPersistenceRecords.ValidationBundleSetRecord> findBundleSets(
            String validationBundleId);

    record BundleAppendResult(
            ChainPersistenceRecords.AuthorityEventRecord event,
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets,
            boolean replayed) {
        public BundleAppendResult {
            sets = List.copyOf(sets);
        }
    }
}
