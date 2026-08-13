package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextSource;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Product composition adapter for the single thirteen-module context source.
 *
 * <p>Each product authority owns only its module projection. This adapter
 * rejects partial or duplicate wiring and always reads in the frozen ordinal
 * order, so no caller can silently omit a module or reorder the model input.</p>
 */
public final class ProductChainContextSourceAdapter
        implements ChainContextSource {
    private final Map<ChainContextModule, ProductChainContextModuleSource>
            sources;

    public ProductChainContextSourceAdapter(
            List<ProductChainContextModuleSource> sources) {
        Objects.requireNonNull(sources, "sources");
        EnumMap<ChainContextModule, ProductChainContextModuleSource> indexed =
                new EnumMap<>(ChainContextModule.class);
        for (ProductChainContextModuleSource source : sources) {
            Objects.requireNonNull(source, "module source");
            ProductChainContextModuleSource duplicate = indexed.putIfAbsent(
                    Objects.requireNonNull(source.module(), "source.module"),
                    source);
            if (duplicate != null) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_DUPLICATE,
                        "duplicate context source for " + source.module());
            }
        }
        if (indexed.size() != ChainContextModule.values().length) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_SOURCE_MODULE_MISSING,
                    "product context source requires all thirteen modules");
        }
        this.sources = Map.copyOf(indexed);
    }

    @Override
    public List<ChainContextSourceSnapshot> project(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChainContextModule> modules = new ArrayList<>(
                List.of(ChainContextModule.values()));
        modules.sort(Comparator.comparingInt(
                ChainContextModule::ordinalCode));
        List<ChainContextSourceSnapshot> snapshots = new ArrayList<>(
                modules.size());
        for (ChainContextModule module : modules) {
            ChainContextSourceSnapshot snapshot = Objects.requireNonNull(
                    sources.get(module).project(request),
                    "module projection");
            if (snapshot.module() != module) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID,
                        "module source returned a projection for "
                                + snapshot.module() + " instead of " + module);
            }
            snapshots.add(snapshot);
        }
        return List.copyOf(snapshots);
    }
}
