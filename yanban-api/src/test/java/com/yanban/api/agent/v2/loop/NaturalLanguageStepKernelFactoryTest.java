package com.yanban.api.agent.v2.loop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.contracts.ToolId;
import org.junit.jupiter.api.Test;

class NaturalLanguageStepKernelFactoryTest {
    @Test
    void projectToolsExposeTheirExactStrictArgumentShapes() {
        String read = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("project.read")).description();
        String search = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("project.search")).description();

        assertTrue(read.contains(
                "{\"path\":\"normalized/existing/path\"}"));
        assertTrue(search.contains(
                "{\"query\":\"literal text up to 256 chars\","
                        + "\"maxResults\":10}"));
        assertTrue(search.contains("maxResults is 1-20"));
    }
}
