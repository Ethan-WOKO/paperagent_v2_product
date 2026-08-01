package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.paper.latex.LatexCitationUsage;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexParserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentLangChain4jTools {

    private static final String TOOL_CALL_ID = "langchain4j-tool-call";
    private static final int DEFAULT_READ_LIMIT = 12000;
    private static final int MAX_READ_LIMIT = 60000;
    private static final int MAX_WRITE_CHARS = 500_000;
    private static final int MAX_SEARCH_RESULTS = 20;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final KbDocumentRepository kbDocuments;
    private final KbChunkRepository kbChunks;
    private final LatexParserService latexParserService;
    private final AgentArtifactService artifactService;
    private final Path workspaceRoot;

    @Autowired
    public AgentLangChain4jTools(ToolRegistry toolRegistry,
                                 ObjectMapper objectMapper,
                                 KbDocumentRepository kbDocuments,
                                 KbChunkRepository kbChunks,
                                 LatexParserService latexParserService,
                                 AgentArtifactService artifactService) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.kbDocuments = kbDocuments;
        this.kbChunks = kbChunks;
        this.latexParserService = latexParserService;
        this.artifactService = artifactService;
        this.workspaceRoot = Path.of("").toAbsolutePath().normalize();
    }

    AgentLangChain4jTools(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this(toolRegistry, objectMapper, null, null, null, null);
    }

    @Tool(name = "search_knowledge", value = "Search the user's uploaded/private knowledge base chunks.")
    public String searchKnowledge(@ToolMemoryId Long userId,
                                  @P(name = "query", value = "Knowledge-base search query") String query,
                                  @P(name = "topK", value = "Number of chunks to return, default 5", required = false) Integer topK) {
        String denied = authorizeAnnotatedInvocation("search_knowledge", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "query", query);
        put(args, "topK", topK);
        return execute(userId, "search_knowledge", args);
    }

    @Tool(name = "search_web", value = "Search the public web for current or externally verifiable facts.")
    public String searchWeb(@ToolMemoryId Long userId,
                            @P(name = "query", value = "Public web search query") String query,
                            @P(name = "topK", value = "Number of web results to return, default 5", required = false) Integer topK) {
        String denied = authorizeAnnotatedInvocation("search_web", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "query", query);
        put(args, "topK", topK);
        return execute(userId, "search_web", args);
    }

    @Tool(name = "recommend_literature", value = "Recommend academic literature with LLM query planning, deduplication, scoring, optional rerank, and explanation.")
    public String recommendLiterature(@ToolMemoryId Long userId,
                                      @P(name = "query", value = "Main academic literature topic or question") String query,
                                      @P(name = "goal", value = "Optional goal such as survey, latest work, method comparison, or citation support", required = false) String goal,
                                      @P(name = "claims", value = "Optional citation claims or evidence needs, separated by newline or semicolon", required = false) String claims,
                                      @P(name = "topK", value = "Number of final recommendations, default 8, max 30", required = false) Integer topK,
                                      @P(name = "candidateK", value = "Candidates to fetch from each source per generated query, default is derived from topK, max 50", required = false) Integer candidateK,
                                      @P(name = "maxQueries", value = "Maximum generated search queries, default 4, max 6", required = false) Integer maxQueries,
                                      @P(name = "analysisLimit", value = "Maximum top literature cards to analyze with LLM before reranking, default 15, max 30", required = false) Integer analysisLimit,
                                      @P(name = "yearFrom", value = "Optional starting publication year", required = false) Integer yearFrom,
                                      @P(name = "includeBibtex", value = "Whether to include BibTeX, default true", required = false) Boolean includeBibtex,
                                      @P(name = "existingBibtex", value = "Optional existing BibTeX to mark already-present papers", required = false) String existingBibtex) {
        String denied = authorizeAnnotatedInvocation("recommend_literature", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "query", query);
        put(args, "goal", goal);
        put(args, "claims", claims);
        put(args, "topK", topK);
        put(args, "candidateK", candidateK);
        put(args, "maxQueries", maxQueries);
        put(args, "analysisLimit", analysisLimit);
        put(args, "yearFrom", yearFrom);
        put(args, "includeBibtex", includeBibtex);
        put(args, "existingBibtex", existingBibtex);
        return execute(userId, "recommend_literature", args);
    }

    @Tool(name = "literature_search_start", value = "Start an asynchronous literature-search task and return task id.")
    public String literatureSearchStart(@ToolMemoryId Long userId,
                                        @P(name = "query", value = "Academic literature search query") String query,
                                        @P(name = "topK", value = "Target number of papers, default 8, max 20", required = false) Integer topK,
                                        @P(name = "yearFrom", value = "Optional starting publication year", required = false) Integer yearFrom,
                                        @P(name = "includeBibtex", value = "Whether to keep BibTeX in results, default true", required = false) Boolean includeBibtex,
                                        @P(name = "clientRequestId", value = "Optional idempotency key", required = false) String clientRequestId,
                                        @P(name = "projectId", value = "Optional project id", required = false) Long projectId) {
        String denied = authorizeAnnotatedInvocation("literature_search_start", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "query", query);
        put(args, "topK", topK);
        put(args, "yearFrom", yearFrom);
        put(args, "includeBibtex", includeBibtex);
        put(args, "clientRequestId", clientRequestId);
        put(args, "projectId", projectId);
        return execute(userId, "literature_search_start", args);
    }

    @Tool(name = "literature_search_status", value = "Get status and progress for an existing literature-search task.")
    public String literatureSearchStatus(@ToolMemoryId Long userId,
                                         @P(name = "taskId", value = "Literature-search task id") Long taskId) {
        String denied = authorizeAnnotatedInvocation("literature_search_status", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        return execute(userId, "literature_search_status", args);
    }

    @Tool(name = "literature_search_result", value = "Read summary and candidate papers from an existing literature-search task.")
    public String literatureSearchResult(@ToolMemoryId Long userId,
                                         @P(name = "taskId", value = "Literature-search task id") Long taskId) {
        String denied = authorizeAnnotatedInvocation("literature_search_result", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        return execute(userId, "literature_search_result", args);
    }

    @Tool(name = "literature_search_cancel", value = "Request cancellation for an existing literature-search task.")
    public String literatureSearchCancel(@ToolMemoryId Long userId,
                                         @P(name = "taskId", value = "Literature-search task id") Long taskId,
                                         @P(name = "cancelReason", value = "Optional cancellation reason", required = false) String cancelReason) {
        String denied = authorizeAnnotatedInvocation("literature_search_cancel", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        put(args, "cancelReason", cancelReason);
        return execute(userId, "literature_search_cancel", args);
    }

    @Tool(name = "paper_polish_status", value = "Get status and stage for an existing paper-polish task.")
    public String paperPolishStatus(@ToolMemoryId Long userId,
                                    @P(name = "taskId", value = "Paper-polish task id") Long taskId) {
        String denied = authorizeAnnotatedInvocation("paper_polish_status", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        return execute(userId, "paper_polish_status", args);
    }

    @Tool(name = "paper_polish_result", value = "Read result summary and artifacts from an existing paper-polish task.")
    public String paperPolishResult(@ToolMemoryId Long userId,
                                    @P(name = "taskId", value = "Paper-polish task id") Long taskId) {
        String denied = authorizeAnnotatedInvocation("paper_polish_result", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        return execute(userId, "paper_polish_result", args);
    }

    @Tool(name = "paper_task_cancel", value = "Request cancellation for an existing paper-polish task.")
    public String paperTaskCancel(@ToolMemoryId Long userId,
                                  @P(name = "taskId", value = "Paper-polish task id") Long taskId,
                                  @P(name = "cancelReason", value = "Optional cancellation reason", required = false) String cancelReason) {
        String denied = authorizeAnnotatedInvocation("paper_task_cancel", userId);
        if (denied != null) return denied;
        ObjectNode args = args();
        put(args, "taskId", taskId);
        put(args, "cancelReason", cancelReason);
        return execute(userId, "paper_task_cancel", args);
    }

    @Tool(name = "read_document", value = "Read a knowledge-base document by id or a text document from the workspace.")
    public String readDocument(@ToolMemoryId Long userId,
                               @P(name = "documentId", value = "Knowledge-base document id", required = false) Long documentId,
                               @P(name = "path", value = "Workspace-relative text document path", required = false) String path,
                               @P(name = "query", value = "Optional keyword filter for knowledge-base chunks", required = false) String query,
                               @P(name = "maxChars", value = "Maximum characters to return, default 12000", required = false) Integer maxChars) {
        String denied = authorizeAnnotatedInvocation("read_document", userId);
        if (denied != null) return denied;
        try {
            int limit = safeReadLimit(maxChars);
            if (documentId != null) {
                return readKnowledgeDocument(userId, documentId, query, limit);
            }
            if (StringUtils.hasText(path)) {
                Path resolved = resolveWorkspacePath(path);
                String content = readTextFile(resolved, limit);
                ObjectNode output = args();
                output.put("path", workspaceRoot.relativize(resolved).toString());
                output.put("truncated", content.length() >= limit);
                output.put("content", content);
                return json(output);
            }
            return failureJson("documentId or path is required");
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    @Tool(name = "write_document", value = "Write a text or Markdown document inside the workspace.")
    public String writeDocument(@ToolMemoryId Long userId,
                                @P(name = "path", value = "Optional output document title or workspace-relative path", required = false) String path,
                                @P(name = "content", value = "Document content to write") String content,
                                @P(name = "append", value = "Append instead of overwrite, default false", required = false) Boolean append) {
        String denied = authorizeAnnotatedInvocation("write_document", userId);
        if (denied != null) return denied;
        if (artifactService != null && userId != null) {
            if (Boolean.TRUE.equals(append)) {
                return failureJson("append is not supported for artifacts yet");
            }
            try {
                ArtifactResponse artifact = artifactService.createToolArtifact(userId, path, content);
                ObjectNode output = args();
                output.put("success", true);
                output.put("artifactId", artifact.id());
                output.put("title", artifact.title());
                output.put("artifactType", artifact.artifactType());
                output.put("downloadUrl", artifact.downloadUrl());
                output.put("downloadFilename", artifact.downloadFilename());
                output.put("downloadContentType", artifact.downloadContentType());
                output.put("preview", content == null ? "" : content.substring(0, Math.min(content.length(), 500)));
                return json(output);
            } catch (Exception ex) {
                return failureJson(ex.getMessage());
            }
        }
        return writeWorkspaceFile(path, content, append);
    }

    @Tool(name = "search_workspace_files", value = "Search workspace file names and optional text content.")
    public String searchWorkspaceFiles(@P(name = "query", value = "Filename or text query") String query,
                                       @P(name = "glob", value = "Optional glob, for example **/*.java", required = false) String glob,
                                       @P(name = "maxResults", value = "Maximum results, default 20", required = false) Integer maxResults,
                                       @P(name = "includeContent", value = "Search inside file content, default true", required = false) Boolean includeContent) {
        String denied = authorizeAnnotatedInvocation("search_workspace_files", null);
        if (denied != null) return denied;
        if (!StringUtils.hasText(query)) {
            return failureJson("query is required");
        }
        int limit = Math.max(1, Math.min(MAX_SEARCH_RESULTS, maxResults == null ? MAX_SEARCH_RESULTS : maxResults));
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        ArrayNode results = objectMapper.createArrayNode();
        try (Stream<Path> stream = Files.walk(workspaceRoot, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> matchesGlob(path, glob))
                    .filter(this::isReadableTextCandidate)
                    .sorted(Comparator.comparing(path -> workspaceRoot.relativize(path).toString()))
                    .forEach(path -> {
                        if (results.size() >= limit) {
                            return;
                        }
                        String relative = workspaceRoot.relativize(path).toString();
                        boolean filenameMatch = relative.toLowerCase(Locale.ROOT).contains(normalizedQuery);
                        String snippet = "";
                        boolean contentMatch = false;
                        if (Boolean.TRUE.equals(includeContent) || includeContent == null) {
                            snippet = matchingSnippet(path, normalizedQuery);
                            contentMatch = StringUtils.hasText(snippet);
                        }
                        if (filenameMatch || contentMatch) {
                            ObjectNode item = results.addObject();
                            item.put("path", relative);
                            item.put("filenameMatch", filenameMatch);
                            item.put("contentMatch", contentMatch);
                            if (StringUtils.hasText(snippet)) {
                                item.put("snippet", snippet);
                            }
                        }
                    });
            ObjectNode output = args();
            output.put("query", query);
            output.put("count", results.size());
            output.set("items", results);
            return json(output);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    @Tool(name = "read_file", value = "Read a UTF-8 text file from the workspace.")
    public String readFile(@P(name = "path", value = "Workspace-relative file path") String path,
                           @P(name = "maxChars", value = "Maximum characters to return, default 12000", required = false) Integer maxChars) {
        String denied = authorizeAnnotatedInvocation("read_file", null);
        if (denied != null) return denied;
        try {
            Path resolved = resolveWorkspacePath(path);
            String content = readTextFile(resolved, safeReadLimit(maxChars));
            ObjectNode output = args();
            output.put("path", workspaceRoot.relativize(resolved).toString());
            output.put("content", content);
            return json(output);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    @Tool(name = "write_file", value = "Write a UTF-8 text file inside the workspace.")
    public String writeFile(@P(name = "path", value = "Workspace-relative file path") String path,
                            @P(name = "content", value = "File content to write") String content,
                            @P(name = "append", value = "Append instead of overwrite, default false", required = false) Boolean append) {
        String denied = authorizeAnnotatedInvocation("write_file", null);
        if (denied != null) return denied;
        return writeWorkspaceFile(path, content, append);
    }

    @Tool(name = "latex_lint", value = "Lint LaTeX text or a workspace .tex file for common structural issues.")
    public String latexLint(@P(name = "content", value = "LaTeX content", required = false) String content,
                            @P(name = "path", value = "Workspace-relative .tex file path", required = false) String path) {
        String denied = authorizeAnnotatedInvocation("latex_lint", null);
        if (denied != null) return denied;
        try {
            String source = StringUtils.hasText(content) ? content : readTextFile(resolveWorkspacePath(path), MAX_READ_LIMIT);
            LatexDocument document = latexParserService.parse(StringUtils.hasText(path) ? path : "input.tex", source, java.util.Map.of());
            ObjectNode output = args();
            output.put("issueCount", document.lintIssues().size());
            ArrayNode issues = output.putArray("issues");
            for (LatexLintIssue issue : document.lintIssues()) {
                ObjectNode item = issues.addObject();
                item.put("severity", issue.severity().name());
                item.put("code", issue.code());
                item.put("message", issue.message());
                item.put("startOffset", issue.startOffset());
                item.put("endOffset", issue.endOffset());
            }
            return json(output);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    @Tool(name = "extract_citations", value = "Extract LaTeX citation commands and keys from text or a workspace .tex file.")
    public String extractCitations(@P(name = "content", value = "LaTeX content", required = false) String content,
                                   @P(name = "path", value = "Workspace-relative .tex file path", required = false) String path) {
        String denied = authorizeAnnotatedInvocation("extract_citations", null);
        if (denied != null) return denied;
        try {
            String source = StringUtils.hasText(content) ? content : readTextFile(resolveWorkspacePath(path), MAX_READ_LIMIT);
            LatexDocument document = latexParserService.parse(StringUtils.hasText(path) ? path : "input.tex", source, java.util.Map.of());
            ObjectNode output = args();
            output.put("citationCount", document.citationUsages().size());
            ArrayNode citations = output.putArray("citations");
            for (LatexCitationUsage citation : document.citationUsages()) {
                ObjectNode item = citations.addObject();
                item.put("command", citation.command());
                ArrayNode keys = item.putArray("keys");
                citation.keys().forEach(keys::add);
                item.put("startOffset", citation.startOffset());
                item.put("endOffset", citation.endOffset());
            }
            return json(output);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    @Tool(name = "list_knowledge_documents", value = "List the user's knowledge-base documents and processing status.")
    public String listKnowledgeDocuments(@ToolMemoryId Long userId,
                                         @P(name = "limit", value = "Maximum documents, default 20", required = false) Integer limit) {
        String denied = authorizeAnnotatedInvocation("list_knowledge_documents", userId);
        if (denied != null) return denied;
        if (userId == null) {
            return failureJson("userId is required");
        }
        int safeLimit = Math.max(1, Math.min(50, limit == null ? 20 : limit));
        List<KbDocument> documents = kbDocuments.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(safeLimit)
                .toList();
        ObjectNode output = args();
        output.put("count", documents.size());
        ArrayNode items = output.putArray("items");
        for (KbDocument document : documents) {
            ObjectNode item = items.addObject();
            item.put("documentId", document.getId());
            item.put("filename", document.getFilename());
            item.put("status", document.getStatus());
            item.put("sourceType", document.getSourceType());
            item.put("isPublic", Boolean.TRUE.equals(document.getIsPublic()));
            item.put("versionStatus", document.getVersionStatus());
            item.put("chunkCount", kbChunks.countByDocumentId(document.getId()));
            if (document.getCreatedAt() != null) {
                item.put("createdAt", document.getCreatedAt().toString());
            }
        }
        return json(output);
    }

    private String authorizeAnnotatedInvocation(String toolName, Long userId) {
        java.util.Set<String> allowedTools = ToolExecutionContext.getResolvedAllowedTools();
        if (allowedTools == null || !allowedTools.contains(toolName)) {
            return failureJson("tool is not allowed by the resolved runtime policy");
        }
        boolean governed = toolRegistry.findDescriptor(toolName)
                .map(descriptor -> descriptor.modelVisible()
                        && descriptor.sideEffectType() != com.yanban.core.tool.ToolDescriptor.SideEffectType.UNKNOWN
                        && descriptor.confirmationPolicy() == com.yanban.core.tool.ToolDescriptor.ConfirmationPolicy.NEVER)
                .orElse(false);
        if (!governed) {
            return failureJson("tool is not governed for model execution");
        }
        if (userId != null && !userId.equals(ToolExecutionContext.getCurrentUserId())) {
            return failureJson("tool execution user context is missing or does not match");
        }
        return null;
    }

    private String execute(Long userId, String toolName, ObjectNode args) {
        try {
            String denied = authorizeAnnotatedInvocation(toolName, userId);
            if (denied != null) return denied;
            if (userId == null) {
                return failureJson("tool execution user context is missing or does not match");
            }
            java.util.Set<String> allowedTools = ToolExecutionContext.getResolvedAllowedTools();
            ToolResult result = toolRegistry.execute(new ToolCall(TOOL_CALL_ID, toolName, args), allowedTools);
            return serialize(result);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    private String serialize(ToolResult result) {
        try {
            if (result != null && result.success()) {
                return objectMapper.writeValueAsString(result.output());
            }
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("success", false)
                    .put("errorCode", result == null || result.errorCode() == null ? "INTERNAL_ERROR" : result.errorCode().name())
                    .put("errorMessage", result == null || !StringUtils.hasText(result.errorMessage()) ? "tool_failed" : result.errorMessage())
                    .put("retryable", result != null && result.retryable()));
        } catch (Exception ex) {
            return "{\"success\":false,\"error\":\"tool_result_serialization_failed\"}";
        }
    }

    private String readKnowledgeDocument(Long userId, Long documentId, String query, int maxChars) {
        if (userId == null) {
            return failureJson("userId is required");
        }
        KbDocument document = kbDocuments.findById(documentId)
                .filter(item -> item.getDeletedAt() == null)
                .filter(item -> Boolean.TRUE.equals(item.getIsPublic()) || userId.equals(item.getUserId()))
                .orElse(null);
        if (document == null) {
            return failureJson("knowledge document not found or not accessible: " + documentId);
        }
        List<KbChunk> chunks = StringUtils.hasText(query)
                ? kbChunks.searchAccessibleVersionedChunks(query.trim(), userId, document.getProjectId(), false, PageRequest.of(0, 30)).stream()
                        .filter(chunk -> documentId.equals(chunk.getDocumentId()))
                        .toList()
                : kbChunks.findByDocumentIdOrderByChunkIndexAsc(documentId, PageRequest.of(0, 80));
        StringBuilder content = new StringBuilder();
        boolean truncated = false;
        for (KbChunk chunk : chunks) {
            String text = chunk.getChunkText() == null ? "" : chunk.getChunkText();
            if (content.length() + text.length() + 2 > maxChars) {
                int remaining = Math.max(0, maxChars - content.length());
                if (remaining > 0) {
                    content.append(text, 0, Math.min(remaining, text.length()));
                }
                truncated = true;
                break;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(text);
        }
        ObjectNode output = args();
        output.put("documentId", document.getId());
        output.put("filename", document.getFilename());
        output.put("status", document.getStatus());
        output.put("chunkCount", chunks.size());
        output.put("truncated", truncated);
        output.put("content", content.toString());
        return json(output);
    }

    private String writeWorkspaceFile(String path, String content, Boolean append) {
        try {
            if (content == null) {
                return failureJson("content is required");
            }
            if (content.length() > MAX_WRITE_CHARS) {
                return failureJson("content is too large: " + content.length());
            }
            Path resolved = resolveWorkspacePath(path);
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Boolean.TRUE.equals(append)) {
                Files.writeString(resolved, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(resolved, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            ObjectNode output = args();
            output.put("path", workspaceRoot.relativize(resolved).toString());
            output.put("bytes", Files.size(resolved));
            output.put("appended", Boolean.TRUE.equals(append));
            return json(output);
        } catch (Exception ex) {
            return failureJson(ex.getMessage());
        }
    }

    private Path resolveWorkspacePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("path is required");
        }
        Path raw = Path.of(path);
        if (raw.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        Path resolved = workspaceRoot.resolve(raw).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("path escapes workspace");
        }
        return resolved;
    }

    private String readTextFile(Path path, int maxChars) throws IOException {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("file not found: " + workspaceRoot.relativize(path));
        }
        if (!isReadableTextCandidate(path)) {
            throw new IllegalArgumentException("file type is not allowed for text reading: " + path.getFileName());
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return content.length() <= maxChars ? content : content.substring(0, maxChars);
    }

    private boolean matchesGlob(Path path, String glob) {
        if (!StringUtils.hasText(glob)) {
            return true;
        }
        return workspaceRoot.getFileSystem().getPathMatcher("glob:" + glob).matches(workspaceRoot.relativize(path));
    }

    private boolean isReadableTextCandidate(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".tex")
                || name.endsWith(".bib")
                || name.endsWith(".json")
                || name.endsWith(".yaml")
                || name.endsWith(".yml")
                || name.endsWith(".xml")
                || name.endsWith(".csv")
                || name.endsWith(".tsv")
                || name.endsWith(".java")
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".js")
                || name.endsWith(".vue")
                || name.endsWith(".py")
                || name.endsWith(".sql")
                || name.endsWith(".properties");
    }

    private String matchingSnippet(Path path, String normalizedQuery) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String lower = content.toLowerCase(Locale.ROOT);
            int index = lower.indexOf(normalizedQuery);
            if (index < 0) {
                return "";
            }
            int start = Math.max(0, index - 120);
            int end = Math.min(content.length(), index + normalizedQuery.length() + 180);
            return content.substring(start, end).replaceAll("\\s+", " ").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private int safeReadLimit(Integer maxChars) {
        return Math.max(1, Math.min(MAX_READ_LIMIT, maxChars == null ? DEFAULT_READ_LIMIT : maxChars));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return failureJson("json_serialization_failed");
        }
    }

    private String failureJson(String message) {
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("success", false)
                    .put("error", StringUtils.hasText(message) ? message : "tool_failed"));
        } catch (Exception ex) {
            return "{\"success\":false,\"error\":\"tool_failed\"}";
        }
    }

    private ObjectNode args() {
        return objectMapper.createObjectNode();
    }

    private void put(ObjectNode node, String name, String value) {
        if (StringUtils.hasText(value)) {
            node.put(name, value);
        }
    }

    private void put(ObjectNode node, String name, Integer value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private void put(ObjectNode node, String name, Long value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private void put(ObjectNode node, String name, Boolean value) {
        if (value != null) {
            node.put(name, value);
        }
    }
}
