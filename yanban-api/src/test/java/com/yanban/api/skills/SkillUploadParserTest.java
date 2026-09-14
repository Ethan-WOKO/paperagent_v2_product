package com.yanban.api.skills;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class SkillUploadParserTest {
    private final SkillUploadParser parser = new SkillUploadParser();

    @Test void defaultsAreReadOnlyAndExplicitEmptyDeniesAll() {
        assertThat(parse("# Review\nRead carefully.", null).allowedTools()).isEqualTo(SkillUploadParser.DEFAULT_TOOLS);
        assertThat(parse("# Review\nRead carefully.", "allowed_tools: []").allowedTools()).isEmpty();
    }

    @Test void handlesFrontmatterAndSeparateMetadataAndExplicitNamePrecedence() {
        var parsed = parser.parse(new SkillInstallRequest("My copy", file("---\nname: original\ndescription: >\n  Some description\nallowed-tools: read_project_file search_knowledge\n---\nRead carefully."),
                new SkillInstallRequest.Upload("skill.yaml", "name: original")));
        assertThat(parsed.name()).isEqualTo("My copy");
        assertThat(parsed.description()).isEqualTo("Some description");
        assertThat(parsed.allowedTools()).containsExactly("read_project_file", "search_knowledge");
        assertThat(parsed.prompt()).startsWith("---");
    }

    @Test void supportsExistingYamlBundleAndCrLfMarkdown() {
        var parsed = parse("# Header\r\nInstructions", "name: bundle\ndescription: Read material\nallowed_tools:\n  - read_project_file");
        assertThat(parsed.name()).isEqualTo("bundle");
        assertThat(parsed.allowedTools()).containsExactly("read_project_file");
    }

    @Test void rejectsMetadataConflictsAndAliasAmbiguity() {
        assertThatThrownBy(() -> parse("---\nname: first\n---\nText", "name: second")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse("---\nallowed-tools: []\n---\n# Review", "allowed_tools: []")).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name: one\nname: two", "!!java.lang.ProcessBuilder {}", "allowed_tools: [\"*\"]",
            "allowed_tools: [read_project_file, read_project_file]", "allowed_tools: [execute_in_sandbox(command)]",
            "allowed_tools: [../read]", "allowed_tools: 42", "allowed_tools: null", "scripts: run.sh",
            "name: [a, b]", "description: true", "allowed_tools: &a [*a]", "[a,b]"})
    void rejectsUnsafeOrInvalidYaml(String yaml) {
        assertThatThrownBy(() -> parse("# Review\nRead carefully", yaml)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void boundsUtf8BytesAndMetadataIndependently() {
        assertThatThrownBy(() -> parse("# Review\n" + "中".repeat(22_000), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
        assertThatThrownBy(() -> parse("# Review", "description: " + "x".repeat(16 * 1024)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
        String byteLimit = "# Review\n" + "中".repeat(21_842) + "a";
        assertThat(byteLimit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(64 * 1024);
        assertThat(parse(byteLimit, null).prompt()).isEqualTo(byteLimit);
        assertThatThrownBy(() -> parse(byteLimit + "b", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("64 KiB");
    }

    @Test void rejects33000AsciiAndAcceptsExactly32000CodePointsWithoutTruncation() {
        String boundary = "# Review\n" + "a".repeat(32_000 - 9);
        assertThat(parse(boundary, null).prompt()).isEqualTo(boundary);
        assertThatThrownBy(() -> parse(boundary + "a", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("32000");
        assertThatThrownBy(() -> parse("# Review\n" + "a".repeat(33_000 - 9), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(413))
                .hasMessageContaining("32000");
    }

    @Test void countsSupplementaryUnicodeAsOneCodePointButStillEnforcesBytes() {
        String unicode = "# Review\n" + "😀".repeat(1_000) + "a".repeat(31_000 - 9);
        assertThat(unicode.length()).isEqualTo(33_000);
        assertThat(unicode.codePointCount(0, unicode.length())).isEqualTo(32_000);
        assertThat(parse(unicode, null).prompt()).isEqualTo(unicode);
        assertThatThrownBy(() -> parse(unicode + "😀", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("32000");
        assertThatThrownBy(() -> parse("# Review\n" + "中".repeat(32_000 - 9), null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("64 KiB");
    }

    @Test void countsOriginalFrontmatterBomAndCrLfTowardRuntimePromptLimit() {
        String prefix = "\uFEFF---\r\nname: Review\r\n---\r\n";
        String markdown = prefix + "x".repeat(32_000 - prefix.length());
        assertThat(parse(markdown, null).prompt()).isEqualTo(markdown);
        assertThatThrownBy(() -> parse(markdown + "x", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("32000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Read", "read.file", "read-file", "read_File", "读取", "1read", "_read"})
    void rejectsToolNamesOutsideRuntimeSchemaInBothMetadataSources(String tool) {
        assertThatThrownBy(() -> parse("# Review", "allowed_tools: [" + tool + "]"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("1–64");
        assertThatThrownBy(() -> parse("---\nname: Review\nallowed-tools: " + tool + "\n---\nInstructions", null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("1–64");
    }

    @Test void accepts64CharacterLowercaseToolNamesButRejects65() {
        String boundary = "r" + "a0_".repeat(21);
        assertThat(parse("# Review", "allowed_tools: [r, " + boundary + "]").allowedTools()).containsExactly("r", boundary);
        assertThatThrownBy(() -> parse("# Review", "allowed_tools: [" + boundary + "a]"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("1–64");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "# Review\n\u0000", "# Review\n\uD800", "---\nname: test\n---\n", "---\nname: test"})
    void rejectsEmptyBinaryMalformedUnicodeAndFrontmatter(String markdown) {
        assertThatThrownBy(() -> parse(markdown, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsPathsExtraToolCountsAndMissingName() {
        assertThatThrownBy(() -> parser.parse(new SkillInstallRequest(null,
                new SkillInstallRequest.Upload("../SKILL.md", "# Review"), null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse("# ../Review", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse("Just instructions", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse("# Review", "allowed_tools: [" + String.join(",", java.util.stream.IntStream.range(0, 33).mapToObj(i -> "tool" + i).toList()) + "]"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private SkillUploadParser.Parsed parse(String markdown, String yaml) {
        return parser.parse(new SkillInstallRequest(null, file(markdown), yaml == null ? null : new SkillInstallRequest.Upload("skill.yaml", yaml)));
    }
    private SkillInstallRequest.Upload file(String text) { return new SkillInstallRequest.Upload("SKILL.md", text); }
}
