package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SearchWebToolExecutor implements ToolExecutor {

    private final ObjectMapper objectMapper;
    private final WebSearchProperties properties;
    private final Map<String, WebSearchClient> clients;
    private final Map<String, CachedSearch> cache = new ConcurrentHashMap<>();
    private final ToolDefinition definition;
    private static final List<String> OFFICIAL_DOMAINS = List.of(
            "openai.com",
            "anthropic.com",
            "google.com",
            "googleblog.com",
            "blog.google",
            "deepmind.google",
            "ai.google.dev",
            "cloud.google.com",
            "meta.com",
            "ai.meta.com",
            "llama.com",
            "microsoft.com",
            "azure.microsoft.com",
            "mistral.ai",
            "cohere.com",
            "x.ai",
            "deepseek.com",
            "bigmodel.cn",
            "zhipuai.cn",
            "qwen.ai",
            "qwenlm.github.io",
            "alibabacloud.com",
            "aliyun.com",
            "moonshot.cn",
            "kimi.com",
            "volcengine.com",
            "doubao.com",
            "baidu.com",
            "cloud.baidu.com",
            "hunyuan.tencent.com",
            "tencentcloud.com",
            "minimaxi.com",
            "01.ai",
            "siliconflow.cn"
    );
    private static final List<String> PRIMARY_TECHNICAL_DOMAINS = List.of(
            "github.com",
            "huggingface.co",
            "arxiv.org",
            "openreview.net",
            "paperswithcode.com"
    );
    private static final List<String> SECONDARY_DOMAINS = List.of(
            "zhihu.com",
            "segmentfault.com",
            "csdn.net",
            "jianshu.com",
            "medium.com",
            "mp.weixin.qq.com",
            "36kr.com",
            "ithome.com",
            "juejin.cn",
            "toutiao.com",
            "biggo.com.tw"
    );

    public SearchWebToolExecutor(ObjectMapper objectMapper,
                                 WebSearchProperties properties,
                                 List<WebSearchClient> clients) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clients = new HashMap<>();
        for (WebSearchClient client : clients) {
            this.clients.put(normalizeProvider(client.provider()), client);
        }
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode fields = parameters.putObject("properties");
        fields.putObject("query")
                .put("type", "string")
                .put("description", "General web search query. Use this for current facts, non-academic web information, products, docs, news, and broad internet research.");
        fields.putObject("topK")
                .put("type", "integer")
                .put("description", "Number of web results to return. Default 5, maximum configured by the server.");
        parameters.putArray("required").add("query");
        this.definition = new ToolDefinition(
                "search_web",
                "Search the public web and return result titles, URLs, snippets, and degradation metadata. For current/latest/time-sensitive questions, degraded=true means external evidence is insufficient and the model must not present memory-based claims as latest facts.",
                parameters
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.arguments() == null ? null : call.arguments().path("query").asText(null);
        int topK = call.arguments() != null && call.arguments().has("topK")
                ? call.arguments().path("topK").asInt(5)
                : 5;
        int limit = Math.max(1, Math.min(Math.max(1, properties.getMaxResults()), topK));
        if (!StringUtils.hasText(query)) {
            return ToolResult.success(call.id(), definition.name(), degradedOutput("", limit, "empty_query"));
        }
        WebSearchClient client = selectClient();
        if (client == null) {
            return ToolResult.success(call.id(), definition.name(), degradedOutput(query.trim(), limit, "web_search_client_unavailable"));
        }
        if (!client.available()) {
            return ToolResult.success(call.id(), definition.name(), degradedOutput(query.trim(), limit, client.unavailableReason()));
        }
        WebSearchResultEnvelope envelope = searchWithCache(client, query.trim(), limit);
        RenderedSearch rendered = toOutput(envelope);
        return new ToolResult(call.id(), definition.name(), true, rendered.output(),
                null, null, false, rendered.evidenceRefs(), List.of(), List.of(), "v1");
    }

    private RenderedSearch toOutput(WebSearchResultEnvelope envelope) {
        WebSearchResponse response = envelope.response();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("query", response.query());
        output.put("source", response.provider());
        output.put("provider", response.provider());
        output.put("degraded", response.degraded());
        output.put("resultCount", response.resultCount());
        output.put("requestedTopK", response.requestedTopK());
        output.put("cacheHit", envelope.cacheHit());
        if (envelope.cacheHit()) {
            output.put("estimatedCreditsCharged", 0);
        }
        if (response.usageCredits() != null) {
            output.put("usageCredits", response.usageCredits());
        }
        if (StringUtils.hasText(response.requestId())) {
            output.put("requestId", response.requestId());
        }
        if (response.responseTimeSeconds() != null) {
            output.put("responseTimeSeconds", response.responseTimeSeconds());
        }
        if (StringUtils.hasText(envelope.fallbackReason())) {
            output.put("fallbackReason", envelope.fallbackReason());
        }
        if (StringUtils.hasText(response.error())) {
            output.put("error", response.error());
        }
        if (response.degraded()) {
            output.put("guidance", "External web search was unavailable or returned no usable results. For current/latest/time-sensitive questions, state that no external web evidence was retrieved and do not present model-memory claims as latest facts. For non-current background questions, a clearly caveated general answer may be acceptable.");
        } else {
            output.put("guidance", "Use these external web results as evidence. For current/latest/time-sensitive facts, prioritize sourceAuthority=official/high or primary_technical/medium-high. Treat secondary/low sources as background only, cite retrieved sources, and do not claim unsupported model-memory facts as latest.");
        }
        output.put("sourcePolicy", "official/high > primary_technical/medium-high > general_web/medium > secondary/low. If only low-authority sources are available, say confidence is limited and recommend checking official vendor pages.");
        ArrayNode items = output.putArray("items");
        LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>();
        List<WebSearchItem> results = response.items() == null ? List.of() : response.items();
        for (WebSearchItem result : rankBySourceCredibility(results)) {
            SourceCredibility credibility = sourceCredibility(result.url());
            ObjectNode item = items.addObject();
            item.put("title", result.title());
            item.put("url", result.url());
            item.put("snippet", result.snippet());
            item.put("sourceAuthority", credibility.authority());
            item.put("sourceType", credibility.type());
            item.put("authorityScore", credibility.score());
            item.put("authorityReason", credibility.reason());
            if (result.score() != null) {
                item.put("score", result.score());
            }
            if (StringUtils.hasText(result.publishedAt())) {
                item.put("publishedAt", result.publishedAt());
            }
            if (StringUtils.hasText(result.source())) {
                item.put("source", result.source());
            }
            String evidenceRef = webEvidenceRef(result.url());
            if (evidenceRef != null) {
                item.put("evidenceRef", evidenceRef);
                evidenceRefs.add(evidenceRef);
            }
        }
        return new RenderedSearch(output, List.copyOf(evidenceRefs));
    }

    private String webEvidenceRef(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toASCIIString().getBytes(StandardCharsets.UTF_8));
            return "web:" + HexFormat.of().formatHex(digest);
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private List<WebSearchItem> rankBySourceCredibility(List<WebSearchItem> results) {
        return results.stream()
                .sorted(Comparator
                        .comparingDouble((WebSearchItem item) -> sourceCredibility(item.url()).score()).reversed()
                        .thenComparing((WebSearchItem item) -> item.score() == null ? 0.0 : item.score(), Comparator.reverseOrder()))
                .toList();
    }

    private ObjectNode degradedOutput(String query, int limit, String error) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("query", query);
        output.put("source", "duckduckgo_html");
        output.put("degraded", true);
        output.put("resultCount", 0);
        output.put("requestedTopK", limit);
        output.put("error", error);
        output.put("guidance", "External web search was unavailable or returned no usable results. For current/latest/time-sensitive questions, state that no external web evidence was retrieved and do not present model-memory claims as latest facts. For non-current background questions, a clearly caveated general answer may be acceptable.");
        output.putArray("items");
        return output;
    }

    private WebSearchClient selectClient() {
        WebSearchClient configured = clients.get(normalizeProvider(properties.getProvider()));
        if (configured != null && configured.available()) {
            return configured;
        }
        if (properties.isFallbackToDuckDuckGo()) {
            WebSearchClient fallback = clients.get("duckduckgo");
            if (fallback != null && fallback.available()) {
                return fallback;
            }
        }
        return configured;
    }

    private WebSearchResultEnvelope searchWithCache(WebSearchClient client, String query, int limit) {
        String fallbackReason = fallbackReason(client);
        String key = normalizeProvider(client.provider()) + "|" + normalizeQuery(query) + "|" + limit;
        CachedSearch cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return new WebSearchResultEnvelope(cached.response(), true, fallbackReason);
        }
        synchronized (cache) {
            cached = cache.get(key);
            if (cached != null && !cached.expired()) {
                return new WebSearchResultEnvelope(cached.response(), true, fallbackReason);
            }
            WebSearchResponse response = client.search(query, limit);
            if (!response.degraded() && response.resultCount() > 0 && cacheEnabled()) {
                cache.put(key, new CachedSearch(response, Instant.now().plus(properties.getCacheTtl())));
            }
            return new WebSearchResultEnvelope(response, false, fallbackReason);
        }
    }

    private String fallbackReason(WebSearchClient selected) {
        String configuredProvider = normalizeProvider(properties.getProvider());
        if (selected == null || configuredProvider.equals(normalizeProvider(selected.provider()))) {
            return "";
        }
        WebSearchClient configured = clients.get(configuredProvider);
        String reason = configured == null ? "provider_not_registered" : configured.unavailableReason();
        return configuredProvider + "_fallback_to_" + selected.provider() + ":" + reason;
    }

    private boolean cacheEnabled() {
        return properties.getCacheTtl() != null && !properties.getCacheTtl().isZero() && !properties.getCacheTtl().isNegative();
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "duckduckgo";
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private SourceCredibility sourceCredibility(String url) {
        String host = host(url);
        if (!StringUtils.hasText(host)) {
            return new SourceCredibility("unknown", "unknown", 0.35, "URL host could not be parsed.");
        }
        if (matchesDomain(host, OFFICIAL_DOMAINS)) {
            return new SourceCredibility("official", "vendor_or_platform_official", 1.0, "Official vendor/platform domain.");
        }
        if (matchesDomain(host, PRIMARY_TECHNICAL_DOMAINS) || host.startsWith("docs.") || host.startsWith("developer.") || host.startsWith("developers.")) {
            return new SourceCredibility("primary_technical", "technical_primary_or_repository", 0.82, "Technical primary source, repository, paper, or documentation host.");
        }
        if (matchesDomain(host, SECONDARY_DOMAINS)) {
            return new SourceCredibility("secondary", "community_or_media", 0.42, "Community/media source; useful as background but not authoritative for latest releases.");
        }
        return new SourceCredibility("general_web", "unclassified", 0.58, "General web source; verify important latest claims against official sources.");
    }

    private String host(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            String normalized = url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url;
            String host = URI.create(normalized).getHost();
            if (!StringUtils.hasText(host)) {
                return "";
            }
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception ex) {
            return "";
        }
    }

    private boolean matchesDomain(String host, List<String> domains) {
        for (String domain : domains) {
            String normalized = domain.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record CachedSearch(WebSearchResponse response, Instant expiresAt) {
        private boolean expired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }

    private record WebSearchResultEnvelope(WebSearchResponse response, boolean cacheHit, String fallbackReason) {
    }

    private record RenderedSearch(ObjectNode output, List<String> evidenceRefs) {
    }

    private record SourceCredibility(String authority, String type, double score, String reason) {
    }
}
