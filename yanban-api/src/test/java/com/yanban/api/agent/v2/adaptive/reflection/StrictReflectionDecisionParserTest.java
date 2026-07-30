package com.yanban.api.agent.v2.adaptive.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class StrictReflectionDecisionParserTest {
    private final StrictReflectionDecisionParser parser =
            new StrictReflectionDecisionParser(new ObjectMapper());

    @TestFactory
    Stream<DynamicTest> parsesEveryAllowedNonTerminalDecision() {
        return Stream.of(
                        ReflectionAction.CONTINUE,
                        ReflectionAction.FAIL)
                .map(action -> DynamicTest.dynamicTest(
                        action.name(),
                        () -> {
                            ReflectionOutcome outcome = parser.parse("""
                                    {"decision":"%s","reason":"bounded reason",
                                     "finalText":null,"replacementSteps":[]}
                                    """.formatted(action));
                            assertEquals(action, outcome.decision());
                            assertEquals("bounded reason", outcome.reason());
                            assertNull(outcome.finalText());
                        }));
    }

    @Test
    void parsesCompleteWithFinalText() {
        ReflectionOutcome outcome = parser.parse("""
                {
                  "decision":"COMPLETE",
                  "reason":"all authoritative work is terminal",
                  "finalText":"The requested work is complete.",
                  "replacementSteps":[]
                }
                """);

        assertEquals(ReflectionAction.COMPLETE, outcome.decision());
        assertEquals(
                "all authoritative work is terminal", outcome.reason());
        assertEquals(
                "The requested work is complete.", outcome.finalText());
    }

    @Test
    void parsesReplanAndMapsPublicCapabilityAlias() {
        ReflectionOutcome outcome = parser.parse("""
                {
                  "decision":"REPLAN",
                  "reason":"the failed approach needs replacement",
                  "finalText":null,
                  "replacementSteps":[{
                    "id":"repair-1",
                    "intent":"Repair the source",
                    "expectedOutcome":"The source compiles",
                    "dependencies":[],
                    "completionCriteria":["Compilation succeeds"],
                    "maxAttempts":2,
                    "maxDurationSeconds":120,
                    "capability":"sandbox_execute"
                  }]
                }
                """);

        assertEquals(ReflectionAction.REPLAN, outcome.decision());
        assertEquals(1, outcome.replacementSteps().size());
        assertEquals(
                "sandbox_execute",
                outcome.replacementSteps().get(0).publicCapability());
        assertEquals(
                "sandbox.execute",
                outcome.replacementSteps().get(0).internalToolId().value());
    }

    @Test
    void rejectsUnknownAndMissingFields() {
        assertInvalid("""
                {"decision":"CONTINUE","reason":"x","finalText":null,
                 "replacementSteps":[],"unexpected":true}
                """);
        assertInvalid("""
                {"decision":"CONTINUE"}
                """);
        assertInvalid("""
                {"decision":"COMPLETE","reason":"x","finalText":null,
                 "replacementSteps":[]}
                """);
        assertInvalid("""
                {"decision":"FAIL","reason":"x","finalText":"not allowed",
                 "replacementSteps":[]}
                """);
        assertInvalid("""
                {"decision":"CONTINUE","reason":"x","finalText":null,
                 "replacementSteps":[{}]}
                """);
    }

    @Test
    void rejectsDuplicateFields() {
        assertInvalid("""
                {"decision":"CONTINUE","decision":"FAIL","reason":"x",
                 "finalText":null,"replacementSteps":[]}
                """);
        assertInvalid("""
                {"decision":"CONTINUE","reason":"x","reason":"y",
                 "finalText":null,"replacementSteps":[]}
                """);
    }

    @Test
    void rejectsInvalidDecisionAndMalformedJson() {
        assertInvalid("""
                {"decision":"RETRY","reason":"x","finalText":null,
                 "replacementSteps":[]}
                """);
        assertInvalid("""
                {"decision":"continue","reason":"x","finalText":null,
                 "replacementSteps":[]}
                """);
        assertInvalid("{not-json");
        assertInvalid("[]");
    }

    @Test
    void rejectsBlankAndOversizedOutput() {
        assertInvalid(null);
        assertInvalid(" ");
        assertInvalid("x".repeat(
                StrictReflectionDecisionParser.MAX_OUTPUT_CHARACTERS + 1));
    }

    @Test
    void rejectsOversizedFields() {
        assertInvalid("""
                {"decision":"CONTINUE","reason":"%s","finalText":null,
                 "replacementSteps":[]}
                """.formatted("x".repeat(2_001)));
        assertInvalid("""
                {"decision":"COMPLETE","reason":"x","finalText":"%s",
                 "replacementSteps":[]}
                """.formatted("x".repeat(20_001)));
    }

    @Test
    void rejectsInvalidReplacementSteps() {
        assertInvalid(replanWithStep(
                "\"id\":\"one\"",
                "\"dependencies\":[]",
                "\"capability\":\"project.read\""));
        assertInvalid(replanWithStep(
                "\"id\":\"one\"",
                "\"dependencies\":[]",
                "\"capability\":\"unknown_tool\""));
        assertInvalid(replanWithStep(
                "\"id\":\"one\"",
                "\"dependencies\":[\"missing\"]",
                "\"capability\":null"));
        String duplicateSteps = replanWithStep(
                "\"id\":\"same\"",
                "\"dependencies\":[]",
                "\"capability\":null").replace(
                        "]}",
                        "},{\"id\":\"same\",\"intent\":\"x\","
                                + "\"expectedOutcome\":\"x\","
                                + "\"dependencies\":[],"
                                + "\"completionCriteria\":[\"x\"],"
                                + "\"maxAttempts\":1,"
                                + "\"maxDurationSeconds\":1,"
                                + "\"capability\":null}]}");
        assertInvalid(duplicateSteps);
    }

    @Test
    void providerBoundaryCarriesOnlyNeutralDto() {
        ReflectionContext context = new ReflectionContext(
                "task frame",
                "plan revision",
                List.of("recent conversation"),
                List.of("completed fact"),
                List.of("receipt"),
                List.of("unfinished step"));
        ReflectionProvider provider =
                ignored -> """
                        {"decision":"CONTINUE","reason":"x",
                         "finalText":null,"replacementSteps":[]}
                        """;

        assertEquals(
                ReflectionAction.CONTINUE,
                parser.parse(provider.reflect(context)).decision());
    }

    private void assertInvalid(String raw) {
        assertThrows(ReflectionParseException.class, () -> parser.parse(raw));
    }

    private String replanWithStep(
            String id, String dependencies, String capability) {
        return """
                {"decision":"REPLAN","reason":"x","finalText":null,
                 "replacementSteps":[{
                   %s,"intent":"x","expectedOutcome":"x",
                   %s,"completionCriteria":["x"],
                   "maxAttempts":1,"maxDurationSeconds":1,%s}]}
                """.formatted(id, dependencies, capability);
    }
}
