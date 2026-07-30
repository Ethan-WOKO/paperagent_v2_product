package com.yanban.api.agent.v2.adaptive.reflection;

/**
 * The complete set of decisions an adaptive reflection provider may return.
 */
public enum ReflectionAction {
    CONTINUE,
    REPLAN,
    COMPLETE,
    FAIL
}
