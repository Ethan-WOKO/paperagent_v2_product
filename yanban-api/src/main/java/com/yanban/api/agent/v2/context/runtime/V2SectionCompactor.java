package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.V2ContextSectionDraft;

@FunctionalInterface
public interface V2SectionCompactor {
    V2SectionCompactionResult compact(V2ContextSectionDraft section);
}
