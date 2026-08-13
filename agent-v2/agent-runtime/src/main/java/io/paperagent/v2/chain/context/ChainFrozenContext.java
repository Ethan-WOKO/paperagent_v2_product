package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Historical model input rebuilt only from the immutable V71 projection. */
public record ChainFrozenContext(
        ContextRevisionRecord revision,
        List<ContextModuleRecord> modules,
        String canonicalPrompt,
        Set<String> visibleSourceRefs) {
    public ChainFrozenContext {
        Objects.requireNonNull(revision, "revision");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        canonicalPrompt = Objects.requireNonNull(canonicalPrompt, "canonicalPrompt");
        visibleSourceRefs = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(
                Objects.requireNonNull(visibleSourceRefs, "visibleSourceRefs")));
        if (revision.status() == ChainContextRevisionStatus.BUILDING) {
            throw new IllegalArgumentException("a BUILDING context is not frozen");
        }
        if (modules.size() != 13) {
            throw new IllegalArgumentException("a frozen context must contain thirteen modules");
        }
        if (canonicalPrompt.isBlank()) {
            throw new IllegalArgumentException("canonicalPrompt must not be blank");
        }
    }
}
