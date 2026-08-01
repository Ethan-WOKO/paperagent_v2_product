package com.yanban.api.agent.v2.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ToolId;
import org.junit.jupiter.api.Test;

class NaturalLanguageStepKernelFactoryTest {
    @Test
    void projectToolsExposeTheirExactStrictArgumentShapes() {
        ObjectValue read = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("project.read")).parameterSchema();
        ObjectValue search = NaturalLanguageStepKernelFactory.descriptor(
                new ToolId("project.search")).parameterSchema();

        assertFalse(((BooleanValue) read.values()
                .get("additionalProperties")).value());
        assertTrue(((ObjectValue) read.values().get("properties"))
                .values().containsKey("path"));
        ObjectValue searchProperties = (ObjectValue) search.values()
                .get("properties");
        assertEquals(256, ((NumberValue) ((ObjectValue) searchProperties
                .values().get("query")).values().get("maxLength"))
                .value().intValueExact());
        assertEquals(20, ((NumberValue) ((ObjectValue) searchProperties
                .values().get("maxResults")).values().get("maximum"))
                .value().intValueExact());
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
        assertTrue(sandbox.contains(
                "Prefer yanban-runner java path.java"));
        assertTrue(sandbox.contains(
                "Direct javac accepts only one"));
        assertTrue(sandbox.contains(
                "Direct java accepts only -version"));
        assertTrue(sandbox.contains(
                "does not accept a compiled class name"));
    }
}
