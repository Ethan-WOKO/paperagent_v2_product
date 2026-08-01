package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, language-neutral prose statistics after conservative markup removal. */
final class V2ProjectPaperLanguageStatsTool {
    static final String KIND = "project.paper.language.stats";
    static final String PARSER = "paper-language-stats@1";
    static final int MAX_PATHS = 30;
    static final int MAX_TOTAL_BYTES = 3_000_000;
    static final int MAX_SECTIONS = 200;
    static final int MAX_LONG_SENTENCES = 300;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "longSentenceWordLikeUnits", "includeSections");
    private static final Pattern LATEX_SECTION = Pattern.compile(
            "^\\s*\\\\(chapter|section|subsection|subsubsection)"
                    + "\\*?\\{([^{}]{1,300})}");
    private static final Pattern MARKDOWN_SECTION = Pattern.compile(
            "^\\s*(#{1,6})\\s+(.{1,300}?)\\s*$");
    private static final Pattern LATEX_COMMAND = Pattern.compile(
            "\\\\[A-Za-z@]+\\*?(?:\\s*\\[[^]]*])?");
    private static final Pattern MATH = Pattern.compile(
            "\\${1,2}[^$\\r\\n]*\\${1,2}"
                    + "|\\\\\\([^\\r\\n]*?\\\\\\)"
                    + "|\\\\\\[[^\\r\\n]*?\\\\\\]");
    private static final Pattern WORD_LIKE = Pattern.compile(
            "\\p{IsHan}|[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");
    private static final Pattern SENTENCE = Pattern.compile(
            "[^.!?。！？]+(?:[.!?。！？]+|$)");

    private final ObjectMapper json;

    V2ProjectPaperLanguageStatsTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectPaperLanguageStatsTool::isPaperText);
        int longThreshold = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "longSentenceWordLikeUnits", 35, 10, 200);
        boolean includeSections =
                V2ProjectAnalysisToolSupport.optionalBoolean(
                        arguments, "includeSections", true);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        ArrayNode files = json.createArrayNode();
        ArrayNode sections = json.createArrayNode();
        ArrayNode longSentences = json.createArrayNode();
        int totalUnits = 0;
        int totalSentences = 0;
        int totalParagraphs = 0;
        for (var source : input.sources()) {
            Prepared prepared = prepare(source.content());
            Stats fileStats = stats(prepared.cleaned());
            totalUnits += fileStats.wordLikeUnits();
            totalSentences += fileStats.sentences();
            totalParagraphs += fileStats.paragraphs();
            ObjectNode file = files.addObject();
            file.put("path", source.path());
            writeStats(file, fileStats);
            addLongSentences(longSentences, source.path(),
                    prepared.cleaned(), longThreshold, 0);

            if (includeSections) {
                List<SectionStart> starts = sectionStarts(
                        prepared.rawLines());
                if (sections.size() + starts.size() > MAX_SECTIONS) {
                    throw V2ProjectAnalysisToolSupport.failed(
                            "section_budget");
                }
                for (int index = 0; index < starts.size(); index++) {
                    SectionStart start = starts.get(index);
                    int endLine = index + 1 < starts.size()
                            ? starts.get(index + 1).line() - 1
                            : prepared.cleanedLines().size();
                    String sectionText = String.join("\n",
                            prepared.cleanedLines().subList(
                                    start.line() - 1, endLine));
                    ObjectNode section = sections.addObject();
                    section.put("path", source.path());
                    section.put("line", start.line());
                    section.put("endLine", endLine);
                    section.put("level", start.level());
                    section.put("title", start.title());
                    writeStats(section, stats(sectionText));
                }
            }
        }
        if (longSentences.size() > MAX_LONG_SENTENCES) {
            throw V2ProjectAnalysisToolSupport.failed(
                    "long_sentence_budget");
        }

        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("parser", PARSER);
        ArrayNode outputPaths = output.putArray("paths");
        paths.forEach(path -> outputPaths.add(path.value()));
        ObjectNode summary = output.putObject("summary");
        summary.put("files", files.size());
        summary.put("sections", sections.size());
        summary.put("wordLikeUnits", totalUnits);
        summary.put("sentences", totalSentences);
        summary.put("paragraphs", totalParagraphs);
        summary.put("longSentences", longSentences.size());
        summary.put("longSentenceWordLikeUnits", longThreshold);
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("markupRemoval", "conservative");
        output.set("files", files);
        output.set("sections", sections);
        output.set("longSentences", longSentences);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static Prepared prepare(String content) {
        String[] raw = content.split("\\R", -1);
        List<String> rawLines = List.of(raw);
        List<String> cleaned = new ArrayList<>(raw.length);
        for (String line : raw) {
            String value = V2ProjectAnalysisToolSupport
                    .withoutLatexComment(line);
            value = MATH.matcher(value).replaceAll(" ");
            value = LATEX_COMMAND.matcher(value).replaceAll(" ");
            value = value.replace('{', ' ').replace('}', ' ')
                    .replace('`', ' ').replace('*', ' ')
                    .replace('_', ' ');
            cleaned.add(value);
        }
        return new Prepared(rawLines, List.copyOf(cleaned),
                String.join("\n", cleaned));
    }

    private static List<SectionStart> sectionStarts(List<String> lines) {
        List<SectionStart> result = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = V2ProjectAnalysisToolSupport
                    .withoutLatexComment(lines.get(index));
            Matcher latex = LATEX_SECTION.matcher(line);
            if (latex.find()) {
                result.add(new SectionStart(index + 1,
                        latex.group(1),
                        V2ProjectAnalysisToolSupport.snippet(
                                latex.group(2))));
                continue;
            }
            Matcher markdown = MARKDOWN_SECTION.matcher(line);
            if (markdown.find()) {
                result.add(new SectionStart(index + 1,
                        "h" + markdown.group(1).length(),
                        V2ProjectAnalysisToolSupport.snippet(
                                markdown.group(2))));
            }
        }
        return List.copyOf(result);
    }

    private static Stats stats(String content) {
        int units = 0;
        Matcher words = WORD_LIKE.matcher(content);
        while (words.find()) {
            units++;
        }
        int sentences = 0;
        Matcher sentenceMatcher = SENTENCE.matcher(content);
        while (sentenceMatcher.find()) {
            if (!sentenceMatcher.group().isBlank()) {
                sentences++;
            }
        }
        int paragraphs = 0;
        for (String paragraph : content.split("(?:\\R\\s*){2,}")) {
            if (!paragraph.isBlank()) {
                paragraphs++;
            }
        }
        int characters = (int) content.codePoints()
                .filter(value -> !Character.isWhitespace(value)).count();
        return new Stats(characters, units, sentences, paragraphs);
    }

    private static void addLongSentences(
            ArrayNode target,
            String path,
            String content,
            int threshold,
            int lineOffset) {
        Matcher matcher = SENTENCE.matcher(content);
        while (matcher.find()) {
            int units = stats(matcher.group()).wordLikeUnits();
            if (units < threshold) {
                continue;
            }
            if (target.size() >= MAX_LONG_SENTENCES) {
                throw V2ProjectAnalysisToolSupport.failed(
                        "long_sentence_budget");
            }
            ObjectNode item = target.addObject();
            item.put("path", path);
            item.put("line", lineOffset
                    + V2ProjectAnalysisToolSupport.lineNumber(
                            content, matcher.start()));
            item.put("wordLikeUnits", units);
        }
    }

    private static void writeStats(ObjectNode target, Stats stats) {
        target.put("characters", stats.characters());
        target.put("wordLikeUnits", stats.wordLikeUnits());
        target.put("sentences", stats.sentences());
        target.put("paragraphs", stats.paragraphs());
    }

    private static boolean isPaperText(String path) {
        return switch (V2ProjectAnalysisToolSupport.extension(path)) {
            case "tex", "md", "txt" -> true;
            default -> false;
        };
    }

    private record Prepared(
            List<String> rawLines,
            List<String> cleanedLines,
            String cleaned) {
    }

    private record SectionStart(int line, String level, String title) {
    }

    private record Stats(
            int characters,
            int wordLikeUnits,
            int sentences,
            int paragraphs) {
    }
}
