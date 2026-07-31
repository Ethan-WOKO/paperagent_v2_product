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

    @Test
    void candidateAndSandboxDescriptionsKeepDeliveryBoundaryExplicit() {
        String candidate = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("project.candidate.compose")).description();
        String sandbox = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("sandbox.execute")).description();

        assertTrue(candidate.contains(
                "only durable source for a reviewable Candidate"));
        assertTrue(candidate.contains(
                "sandbox.execute cannot create a Candidate"));
        assertTrue(sandbox.contains("execution only"));
        assertTrue(sandbox.contains(
                "cannot create or update a Project Candidate"));
        assertTrue(sandbox.contains(
                "prior completed Plan Step created a Candidate"));
        assertTrue(sandbox.contains(
                "run that resulting isolated Workspace"));
    }
}
