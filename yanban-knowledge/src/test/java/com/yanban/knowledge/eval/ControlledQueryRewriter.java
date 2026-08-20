package com.yanban.knowledge.eval;

import java.util.List;

/** Produces validated supplemental queries. The original query is always retained by the caller. */
@FunctionalInterface
public interface ControlledQueryRewriter {
    List<String> rewrite(String query);
}
