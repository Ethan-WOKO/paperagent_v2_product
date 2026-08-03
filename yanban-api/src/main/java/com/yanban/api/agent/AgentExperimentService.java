package com.yanban.api.agent;

import com.yanban.knowledge.eval.KnowledgeSearchServiceContentRetriever;
import com.yanban.knowledge.service.KnowledgeSearchService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import java.util.ArrayList;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentExperimentService {

    private static final int DEFAULT_RAG_TOP_K = 6;

    private final KnowledgeSearchService knowledgeSearchService;
    private final LangChain4jChatModelAdapter langChain4jChatModelAdapter;

    public AgentExperimentService(KnowledgeSearchService knowledgeSearchService,
                                  LangChain4jChatModelAdapter langChain4jChatModelAdapter) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.langChain4jChatModelAdapter = langChain4jChatModelAdapter;
    }

    public AgentExperimentContext prepare(Long userId, String content, AgentExperimentRequest request) {
        AgentSelectedModesDebug selectedModes = new AgentSelectedModesDebug(
                defaultRuntimeMode(request),
                defaultRagMode(request),
                defaultMemoryMode(request),
                defaultToolCallingMode(request)
        );
        if (request == null || !request.isEnabled()) {
            return new AgentExperimentContext(request, selectedModes, null);
        }

        AgentRagExperimentResult ragResult = null;
        if (shouldRunRagExperiment(content)) {
            ragResult = runLangChain4jAugmentorRag(userId, content);
        }
        return new AgentExperimentContext(request, selectedModes, ragResult);
    }

    public AgentDebugPayload toDebugPayload(AgentExperimentContext context) {
        if (context == null || !context.enabled()) {
            return null;
        }
        List<AgentRetrievedChunkDebug> retrievedChunks = context.hasFlag(AgentDebugFlag.SHOW_RETRIEVED_CHUNKS)
                ? context.ragResult() == null ? List.of() : context.ragResult().retrievedChunks()
                : List.of();
        String injectedContext = context.hasFlag(AgentDebugFlag.SHOW_INJECTED_CONTEXT) && context.ragResult() != null
                ? context.ragResult().ragContext()
                : null;
        List<String> debugFlags = context.debugFlags().stream()
                .map(Enum::name)
                .toList();
        return new AgentDebugPayload(
                context.selectedModes(),
                retrievedChunks,
                injectedContext,
                null,
                debugFlags,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                null
        );
    }

    private boolean shouldRunRagExperiment(String content) {
        return StringUtils.hasText(content);
    }

    private AgentRagExperimentResult runLangChain4jAugmentorRag(Long userId, String content) {
        KnowledgeSearchServiceContentRetriever retriever =
                new KnowledgeSearchServiceContentRetriever(knowledgeSearchService, userId, DEFAULT_RAG_TOP_K);
        DefaultRetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(new CompressingQueryTransformer(langChain4jChatModelAdapter))
                .queryRouter(new DefaultQueryRouter(retriever))
                .contentAggregator(new DefaultContentAggregator())
                .contentInjector(new DefaultContentInjector())
                .build();
        UserMessage userMessage = UserMessage.from(content);
        AugmentationResult result = augmentor.augment(new AugmentationRequest(
                userMessage,
                Metadata.from(userMessage, userId, List.<ChatMessage>of())
        ));
        List<AgentRetrievedChunkDebug> chunks = (result == null ? List.<Content>of() : result.contents()).stream()
                .map(this::toChunkDebug)
                .sorted(Comparator.comparing(AgentRetrievedChunkDebug::score,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AgentRetrievedChunkDebug::documentId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgentRetrievedChunkDebug::chunkIndex,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgentRetrievedChunkDebug::citationId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String injectedContext = buildInjectedContext(chunks, "langchain4j-augmentor");
        if (!StringUtils.hasText(injectedContext)) {
            injectedContext = extractAugmentedText(result == null ? null : result.chatMessage());
        }
        return new AgentRagExperimentResult(
                injectedContext, chunks, selectionRefs(chunks));
    }

    private AgentRetrievedChunkDebug toChunkDebug(Content content) {
        String text = normalizeContent(content.textSegment().text());
        Object score = content.metadata().get("score");
        Double numericScore = score instanceof Number number ? number.doubleValue() : null;
        return new AgentRetrievedChunkDebug(
                metadataString(content, KnowledgeSearchServiceContentRetriever.META_SOURCE, "knowledge_base"),
                metadataLong(content, KnowledgeSearchServiceContentRetriever.META_DOCUMENT_ID),
                metadataString(content, KnowledgeSearchServiceContentRetriever.META_FILENAME, null),
                metadataInteger(content, KnowledgeSearchServiceContentRetriever.META_CHUNK_INDEX),
                metadataString(content, KnowledgeSearchServiceContentRetriever.META_CITATION_ID, null),
                numericScore,
                text,
                metadataInteger(content, KnowledgeSearchServiceContentRetriever.META_VERSION_NO),
                metadataString(content, KnowledgeSearchServiceContentRetriever.META_VERSION_STATUS, null)
        );
    }

    private List<AgentRagSelectionRef> selectionRefs(
            List<AgentRetrievedChunkDebug> chunks) {
        List<AgentRagSelectionRef> refs = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            AgentRetrievedChunkDebug chunk = chunks.get(index);
            String contentDigest = sha256(defaultString(chunk.content(), ""));
            String stableId = chunk.documentId() == null
                    ? "rag:unpersisted:" + contentDigest + ":"
                            + (chunk.chunkIndex() == null ? "unknown" : chunk.chunkIndex())
                    : "rag:" + chunk.documentId() + ":"
                            + (chunk.chunkIndex() == null ? "unknown" : chunk.chunkIndex());
            String version = chunk.versionNo() == null
                    ? "legacy" : String.valueOf(chunk.versionNo());
            String canonical = String.join("\n",
                    stableId,
                    version,
                    defaultString(chunk.versionStatus(), ""),
                    defaultString(chunk.content(), ""));
            refs.add(new AgentRagSelectionRef(
                    stableId, version, index + 1, sha256(canonical)));
        }
        return List.copyOf(refs);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String buildInjectedContext(List<AgentRetrievedChunkDebug> chunks, String modeLabel) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("RAG mode: " + modeLabel);
        lines.add("Use the following retrieved knowledge snippets when they are relevant. Cite the supporting filename/citation id when you rely on them.");
        for (int i = 0; i < chunks.size(); i++) {
            AgentRetrievedChunkDebug chunk = chunks.get(i);
            lines.add(("[%d] source=%s file=%s chunk=%s citation=%s score=%s")
                    .formatted(
                            i + 1,
                            defaultString(chunk.source(), "knowledge_base"),
                            defaultString(chunk.filename(), "unknown"),
                            chunk.chunkIndex() == null ? "?" : chunk.chunkIndex(),
                            defaultString(chunk.citationId(), "n/a"),
                            chunk.score() == null ? "n/a" : String.format(Locale.ROOT, "%.4f", chunk.score())
                    ));
            lines.add(defaultString(chunk.content(), ""));
        }
        return String.join("\n", lines).trim();
    }

    private String extractAugmentedText(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return normalizeContent(userMessage.singleText());
        }
        return message == null ? null : normalizeContent(message.toString());
    }

    private AgentRuntimeMode defaultRuntimeMode(AgentExperimentRequest request) {
        return AgentRuntimeMode.LANGCHAIN4J;
    }

    private AgentRagMode defaultRagMode(AgentExperimentRequest request) {
        return request == null || request.ragMode() == null ? AgentRagMode.LANGCHAIN4J_AUGMENTOR : request.ragMode();
    }

    private AgentMemoryMode defaultMemoryMode(AgentExperimentRequest request) {
        return AgentMemoryMode.CONTEXT_PACKER;
    }

    private AgentToolCallingMode defaultToolCallingMode(AgentExperimentRequest request) {
        return AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING;
    }

    private String metadataString(Content content, String key, String fallback) {
        String stringValue = content.textSegment().metadata().getString(key);
        return stringValue.isBlank() ? fallback : stringValue;
    }

    private Long metadataLong(Content content, String key) {
        return content.textSegment().metadata().containsKey(key)
                ? content.textSegment().metadata().getLong(key)
                : null;
    }

    private Integer metadataInteger(Content content, String key) {
        return content.textSegment().metadata().containsKey(key)
                ? content.textSegment().metadata().getInteger(key)
                : null;
    }

    private String normalizeContent(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
