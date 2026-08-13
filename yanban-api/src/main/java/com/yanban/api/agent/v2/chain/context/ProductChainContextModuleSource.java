package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;

import java.util.Objects;

/**
 * Validated product projector for one fixed authority module.
 *
 * <p>The reader supplies typed authority values only. The runtime remains the
 * sole owner of canonical JSON, required version vectors, EMPTY watermarks and
 * visible-source-reference derivation.</p>
 */
public final class ProductChainContextModuleSource {
    private final ChainContextModule module;
    private final ProductChainContextAuthorityReader reader;

    public ProductChainContextModuleSource(
            ChainContextModule module,
            ProductChainContextAuthorityReader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public ChainContextModule module() {
        return module;
    }

    ChainContextSourceSnapshot project(
            ChainContextProjectionRequest request) {
        ProductChainContextAuthorityProjection projection =
                Objects.requireNonNull(reader.read(
                                Objects.requireNonNull(request, "request")),
                        "authority projection");
        return new ChainContextSourceSnapshot(
                module,
                projection.presenceKind(),
                projection.sourceVersionComponents(),
                projection.readBoundaryComponents(),
                projection.projectionVersion(),
                projection.paginationVersion(),
                projection.projectionParameters(),
                projection.projectionFields(),
                projection.emptyWatermark());
    }
}
