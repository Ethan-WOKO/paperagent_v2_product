package io.paperagent.v2.chain.context;

import java.util.List;

/**
 * Product boundary that projects all authority sources at one frozen read cut.
 * A retry of the same BUILDING revision must reproduce the cut identified by
 * {@code buildingRevision.createdAt()} and its frozen identities; it must not
 * silently substitute current-latest authority values.
 */
@FunctionalInterface
public interface ChainContextSource {
    List<ChainContextSourceSnapshot> project(ChainContextProjectionRequest request);
}
