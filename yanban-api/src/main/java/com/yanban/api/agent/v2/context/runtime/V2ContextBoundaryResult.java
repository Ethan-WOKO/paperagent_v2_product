package com.yanban.api.agent.v2.context.runtime;

public sealed interface V2ContextBoundaryResult
        permits V2ContextBoundaryPrepared, V2ContextBoundaryFailure { }
