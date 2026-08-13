package io.paperagent.v2.chain.model;

/** Product-side provider boundary for one chain model attempt. */
@FunctionalInterface
public interface ChainModelCallPort {
    ChainModelCallResult call(ChainModelCallRequest request);
}
