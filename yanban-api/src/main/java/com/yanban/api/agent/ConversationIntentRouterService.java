package com.yanban.api.agent;

import com.yanban.paper.literature.AdHocLiteratureSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureItem;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureSearchResult;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConversationIntentRouterService {

    private static final List<String> PAPER_REVISION_KEYWORDS = List.of(
            "润色论文", "修改论文", "帮我润色", "帮我修改", "论文修改", "论文润色",
            "polish paper", "revise paper", "paper revision", "revise my paper"
    );
    private static final List<String> EXPLICIT_LITERATURE_PREFIXES = List.of("/literature", "/lit", "/文献检索", "@literature");
    private static final List<String> LITERATURE_KEYWORDS = List.of(
            "找文献", "查文献", "文献检索", "推荐文献", "推荐论文", "相关文献", "相关论文", "补几篇引用", "补充引用",
            "bibtex", "参考文献", "literature review", "recent papers", "find papers", "recommend papers", "related work"
    );
    private static final List<String> SEMANTIC_LITERATURE_PATTERNS = List.of(
            "有没有人做过", "有没有做过", "有人研究", "最新工作", "最近有什么工作", "有哪些工作", "这个方向", "这个问题有人", "state of the art", "sota"
    );
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(篇|papers?|references?|refs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINCE_YEAR_PATTERN = Pattern.compile("(20\\d{2})\\s*(年以来|以后|之后|至今|since|after)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECENT_YEARS_PATTERN = Pattern.compile("近\\s*([一二三四五六七八九十\\d]{1,2})\\s*年");

    private final PaperRevisionIntentService paperRevisionIntentService;
    private final AdHocLiteratureSearchService literatureSearchService;

    public ConversationIntentRouterService(PaperRevisionIntentService paperRevisionIntentService,
                                           AdHocLiteratureSearchService literatureSearchService) {
        this.paperRevisionIntentService = paperRevisionIntentService;
        this.literatureSearchService = literatureSearchService;
    }

    public IntentAction route(String content) {
        if (!StringUtils.hasText(content)) return null;
        PaperRevisionIntentService.PaperRevisionSuggestion paperSuggestion = paperRevisionIntentService.suggest(content);
        if (paperSuggestion != null) {
            return new IntentAction(paperSuggestion.url(), paperSuggestion.assistantMessage(), "PAPER_REVISION", 0.95);
        }
        LiteratureIntent literatureIntent = detectLiteratureIntent(content);
        if (literatureIntent == null) return null;
        if (!StringUtils.hasText(literatureIntent.query()) || normalizedLength(literatureIntent.query()) < 4) {
            return new IntentAction(null,
                    "我理解你想做文献检索，但还缺少具体主题。你可以这样说：`/literature polarimetric FDA-MIMO self-protection jamming`，或直接给出中文关键词。",
                    "LITERATURE_SEARCH_CLARIFY", literatureIntent.confidence());
        }
        if (literatureIntent.confidence() < 0.8) {
            return new IntentAction(null,
                    "我猜你可能想检索相关文献。为避免误触发，请回复：`/literature " + literatureIntent.query() + "`，或补充检索主题、数量和年份范围。",
                    "LITERATURE_SEARCH_CONFIRM", literatureIntent.confidence());
        }
        AdHocLiteratureSearchResult result = literatureSearchService.search(literatureIntent.query(), literatureIntent.count(), literatureIntent.yearFrom());
        return new IntentAction(null, formatLiteratureResult(result, literatureIntent.includeBibtex()), "LITERATURE_SEARCH", literatureIntent.confidence());
    }

    private LiteratureIntent detectLiteratureIntent(String content) {
        String trimmed = content.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : EXPLICIT_LITERATURE_PREFIXES) {
            if (normalized.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return new LiteratureIntent(cleanQuery(trimmed.substring(prefix.length())), parseCount(trimmed), parseYearFrom(trimmed), true, 0.98);
            }
        }
        boolean keywordMatched = LITERATURE_KEYWORDS.stream().anyMatch(normalized::contains);
        if (keywordMatched) {
            return new LiteratureIntent(cleanQuery(trimmed), parseCount(trimmed), parseYearFrom(trimmed), wantsBibtex(normalized), 0.86);
        }
        boolean semanticMatched = SEMANTIC_LITERATURE_PATTERNS.stream().anyMatch(normalized::contains);
        if (semanticMatched) {
            return new LiteratureIntent(cleanQuery(trimmed), parseCount(trimmed), parseYearFrom(trimmed), wantsBibtex(normalized), 0.72);
        }
        return null;
    }

    private String cleanQuery(String content) {
        String query = content == null ? "" : content;
        for (String prefix : EXPLICIT_LITERATURE_PREFIXES) {
            query = query.replaceFirst("(?i)^\\s*" + Pattern.quote(prefix) + "\\s*[:：-]?\\s*", "");
        }
        for (String keyword : LITERATURE_KEYWORDS) {
            query = query.replace(keyword, " ");
        }
        for (String pattern : SEMANTIC_LITERATURE_PATTERNS) {
            query = query.replace(pattern, " ");
        }
        query = query.replaceAll("(?i)bibtex|top\\s*\\d+|\\d{1,2}\\s*(篇|papers?|references?|refs?)", " ");
        query = query.replaceAll("近\\s*[一二三四五六七八九十\\d]{1,2}\\s*年", " ");
        query = query.replaceAll("20\\d{2}\\s*(年以来|以后|之后|至今|since|after)?", " ");
        query = query.replaceAll("[，。！？；;,.?！：:]+", " ");
        query = query.replaceAll("\\s+", " ").trim();
        return query;
    }

    private int parseCount(String content) {
        Matcher matcher = COUNT_PATTERN.matcher(content == null ? "" : content);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(20, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return 8;
            }
        }
        return 8;
    }

    private Integer parseYearFrom(String content) {
        String value = content == null ? "" : content;
        Matcher recent = RECENT_YEARS_PATTERN.matcher(value);
        if (recent.find()) {
            int years = chineseNumber(recent.group(1));
            if (years > 0) return Year.now().getValue() - years;
        }
        Matcher since = SINCE_YEAR_PATTERN.matcher(value);
        while (since.find()) {
            String suffix = since.group(2);
            if (suffix != null && !suffix.isBlank()) {
                return Integer.parseInt(since.group(1));
            }
        }
        return null;
    }

    private int chineseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1;
                case "二", "两" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> 0;
            };
        }
    }

    private boolean wantsBibtex(String normalized) {
        return normalized.contains("bibtex") || normalized.contains("bib");
    }

    private int normalizedLength(String value) {
        return value == null ? 0 : value.replaceAll("\\s+", "").length();
    }

    private String formatLiteratureResult(AdHocLiteratureSearchResult result, boolean includeBibtex) {
        StringBuilder sb = new StringBuilder();
        sb.append("已按主题 `").append(result.query()).append("` 检索公开文献。")
                .append("候选 ").append(result.rawCandidateCount()).append(" 条，去重后 ").append(result.uniqueCandidateCount()).append(" 条。\n\n");
        if (!result.sourceFailures().isEmpty()) {
            sb.append("检索源警告：").append(String.join("; ", result.sourceFailures())).append("\n\n");
        }
        if (result.items().isEmpty()) {
            sb.append("未找到足够可靠的候选。建议换用更具体的英文关键词，或放宽年份限制。");
            return sb.toString();
        }
        int index = 1;
        for (AdHocLiteratureItem item : result.items()) {
            sb.append(index++).append(". ").append(item.title()).append("\n");
            sb.append("   - 年份/来源：").append(item.year() == null ? "未知" : item.year()).append(" / ").append(blankToDefault(item.venue(), item.source())).append("\n");
            if (!item.authors().isEmpty()) sb.append("   - 作者：").append(String.join(", ", item.authors().stream().limit(4).toList())).append(item.authors().size() > 4 ? " 等" : "").append("\n");
            if (StringUtils.hasText(item.doi())) sb.append("   - DOI：").append(item.doi()).append("\n");
            if (StringUtils.hasText(item.arxivId())) sb.append("   - arXiv：").append(item.arxivId()).append("\n");
            if (StringUtils.hasText(item.url())) sb.append("   - URL：").append(item.url()).append("\n");
            sb.append("   - 相关性分数：").append(String.format(Locale.ROOT, "%.2f", item.score())).append("\n");
            sb.append("   - Citation status: ").append(item.citationStatus()).append("\n");
            sb.append("   - Metadata risk: ").append(item.metadataRiskLevel());
            if (!item.metadataRiskNotes().isEmpty()) {
                sb.append(" (").append(String.join("; ", item.metadataRiskNotes())).append(")");
            }
            sb.append("\n");
            sb.append("   - Deduplication: ").append(item.duplicateStatus()).append(" / ").append(item.deduplicationKey()).append("\n");
            if (!item.rankingBasis().isEmpty()) {
                sb.append("   - Ranking basis: ").append(String.join("; ", item.rankingBasis())).append("\n");
            }
            if (StringUtils.hasText(item.abstractText())) sb.append("   - 摘要片段：").append(abbreviate(item.abstractText(), 220)).append("\n");
            if (includeBibtex) sb.append("```bibtex\n").append(item.bibtex()).append("```\n");
            sb.append("\n");
        }
        sb.append("注意：以上文献来自公开检索源，投稿前请核对题录、DOI 和引用适配性。");
        return sb.toString();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record IntentAction(String navigationUrl, String assistantMessage, String intent, double confidence) {
    }

    private record LiteratureIntent(String query, int count, Integer yearFrom, boolean includeBibtex, double confidence) {
    }
}
