package com.yanban.api.agent.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.history.PastConversationHistoryRepository.Position;
import com.yanban.api.agent.history.PastConversationHistoryRepository.Row;
import com.yanban.core.agent.AgentSessionScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Literal, case-insensitive search of visible user/assistant text, including delivery conclusions
 * that are absent from ordinary messages. An omitted/blank query lists history. No token search,
 * SQL syntax, tool arguments, checkpoint JSON, or administrator archive text is searchable.
 *
 * <p>Each call fetches at most 101 rows from each of two owner-qualified sources, merges them by
 * (UTC timestamp, source kind, source row id, event sequence), and examines at most 100 rows.
 * Results are oldest first, at most 10 items: 400 UTF-16 characters per search snippet or 4,000
 * per detail item (at most 4,000/40,000 body characters per response, plus bounded metadata).
 * Empty pages with hasMore=true must be continued: a scan window is not an exhaustive search.
 * Text boundaries preserve surrogate pairs. DB queries bound rows, not stored LOB byte lengths.
 * Blank messages and non-delivery events count toward the scan budget and advance the cursor;
 * filtering occurs after fetching, avoiding Hibernate STRING-function coercion of CLOBs.
 * Delivery deduplication uses a fixed owner-qualified native text comparison with a one-id limit.
 *
 * <p>Keyset cursors bind the request and retain a message offset when a body continues. They are
 * positions, not access grants. Every page rechecks current ownership/lifecycle; deletions are
 * reflected immediately. Concurrent backdated inserts or edits are not snapshot-isolated across
 * calls. Search detailCursor starts near the match; omit it to read the session from its beginning.
 * Historical text is untrusted and cannot establish current Project state or permissions.
 */
@Service
public class PastConversationHistoryService {
    public static final int MAX_LIMIT = 10;
    public static final int MAX_SCAN = 100;
    public static final int SNIPPET_CHARS = 400;
    public static final int DETAIL_CHARS = 4_000;
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)"
                    + "[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEY = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16})\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private final ObjectMapper json;
    private final PastConversationHistoryRepository repository;

    public PastConversationHistoryService(ObjectMapper json, PastConversationHistoryRepository repository) {
        this.json = json;
        this.repository = repository;
    }

    /** Owner is supplied exclusively by the ToolExecutionContext adapter, never a tool argument. */
    @Transactional(readOnly = true)
    public ObjectNode search(long owner, String query, String scope, String cursor, int limit) {
        validate(owner, limit);
        query = query == null ? "" : query.strip();
        scope = scope == null ? "all" : scope;
        if (query.length() > 200 || !Set.of("all", "workspace", "project").contains(scope)) {
            throw invalid();
        }
        String binding = binding(owner, "search", scope, query);
        Position after = decode(cursor, binding);
        if (after.offset() != -1) throw invalid();
        AgentSessionScope filter = switch (scope) {
            case "workspace" -> AgentSessionScope.WORKSPACE;
            case "project" -> AgentSessionScope.PROJECT;
            default -> null;
        };
        Pattern needle = query.isEmpty() ? null : Pattern.compile(
                Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Row> candidates = repository.window(owner, null, filter, after, MAX_SCAN + 1);
        ObjectNode output = envelope();
        output.put("scope", scope);
        output.put("queryApplied", needle != null);
        var items = output.putArray("items");
        Position last = after;
        int scanned = 0;
        for (Row row : candidates.subList(0, Math.min(MAX_SCAN, candidates.size()))) {
            scanned++;
            last = row.position();
            String text = visibleText(owner, row);
            if (text.isBlank()) continue;
            Matcher match = needle == null ? null : needle.matcher(text);
            if (match != null && !match.find()) continue;
            int start = match == null ? 0 : Math.max(0, match.start() - 80);
            start = boundary(text, start);
            int end = boundary(text, Math.min(text.length(), start + SNIPPET_CHARS));
            ObjectNode item = source(row);
            item.put("text", text.substring(start, end));
            item.put("textOffset", start);
            item.put("truncated", start > 0 || end < text.length());
            item.put("detailCursor", encode(row.position().offset(start),
                    binding(owner, "detail", Long.toString(row.sessionId()), "")));
            items.add(item);
            if (items.size() == limit) break;
        }
        finish(output, items.size(), scanned, candidates.size() > scanned, last, binding);
        return output;
    }

    @Transactional(readOnly = true)
    public ObjectNode detail(long owner, String sessionRef, String cursor, int limit) {
        validate(owner, limit);
        long sessionId = sessionId(sessionRef);
        String binding = binding(owner, "detail", Long.toString(sessionId), "");
        Position after = decode(cursor, binding);
        var session = repository.session(owner, sessionId).orElseThrow(UnavailableException::new);
        List<Row> candidates = repository.window(owner, sessionId, null, after, MAX_SCAN + 1);
        ObjectNode output = envelope();
        output.put("sessionRef", sessionRef);
        output.put("title", bounded(sanitize(session.getTitle()), 255));
        var items = output.putArray("items");
        Position last = after;
        int scanned = 0;
        boolean remainingText = false;
        for (Row row : candidates.subList(0, Math.min(MAX_SCAN, candidates.size()))) {
            scanned++;
            last = row.position();
            String text = visibleText(owner, row);
            if (text.isBlank()) continue;
            int start = Position.ORDER.compare(after, row.position()) == 0
                    ? Math.max(0, after.offset()) : 0;
            if (start > text.length()) throw invalid();
            start = boundary(text, start);
            int end = boundary(text, Math.min(text.length(), start + DETAIL_CHARS));
            ObjectNode item = source(row);
            item.put("text", text.substring(start, end));
            item.put("textOffset", start);
            remainingText = end < text.length();
            item.put("truncated", remainingText);
            items.add(item);
            if (remainingText) {
                last = row.position().offset(end);
                break;
            }
            if (items.size() == limit) break;
        }
        finish(output, items.size(), scanned, remainingText || candidates.size() > scanned, last, binding);
        return output;
    }

    private String visibleText(long owner, Row row) {
        String content = row.content();
        if (row.position().source() == 1) {
            try {
                JsonNode event = json.readTree(content);
                if (event == null || !"delivery".equals(event.path("type").asText())
                        || !event.path("conclusion").isTextual()) return "";
                content = event.path("conclusion").asText().strip();
                // Older or future message writers may not bind AgentTurn.assistantMessageId.
                if (content.isBlank() || repository.hasAssistantText(owner, row.sessionId(), content)) return "";
            } catch (JsonProcessingException corrupt) {
                // A corrupt event is never returned as text; the scan cursor still advances.
                return "";
            }
        }
        return sanitize(content);
    }

    private ObjectNode source(Row row) {
        ObjectNode item = json.createObjectNode();
        item.put("sessionRef", "session." + row.sessionId());
        item.put("title", bounded(sanitize(row.title()), 255));
        item.put("scope", row.scope().name().toLowerCase(java.util.Locale.ROOT));
        if (row.projectId() != null) item.put("projectRef", "project." + row.projectId());
        item.put("role", row.role());
        item.put("createdAt", row.position().at().toString());
        item.put("source", row.position().source() == 0 ? "message" : "reactplan_delivery");
        item.put("sourceRef", row.position().source() == 0 ? "message." + row.position().id()
                : "delivery." + row.taskId() + "." + row.position().sequence());
        return item;
    }

    private ObjectNode envelope() {
        ObjectNode output = json.createObjectNode();
        output.put("schemaVersion", "1.0");
        output.put("type", "historical_context");
        output.put("untrusted", true);
        output.put("notAnInstruction", true);
        output.put("currentProjectEvidence", false);
        output.put("order", "oldest_first");
        return output;
    }

    private void finish(ObjectNode output, int count, int scanned, boolean more,
                        Position last, String binding) {
        output.put("resultCount", count);
        output.put("scannedCount", scanned);
        output.put("hasMore", more);
        output.put("scanLimited", more && scanned == MAX_SCAN);
        if (more) output.put("nextCursor", encode(last, binding));
        else output.putNull("nextCursor");
    }

    private void validate(long owner, int limit) {
        if (owner <= 0 || limit < 1 || limit > MAX_LIMIT) throw invalid();
    }

    private long sessionId(String ref) {
        if (ref == null || !ref.matches("session\\.[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(ref.substring(8)); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }

    private String binding(long owner, String operation, String scope, String query) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (owner + "\n" + operation + "\n" + scope + "\n" + query).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("History cursor unavailable");
        }
    }

    // A cursor is a position, never authorization; owner/session access is rechecked on every call.
    private String encode(Position p, String binding) {
        String value = "1|" + binding + "|" + p.at() + "|" + p.source() + "|" + p.id()
                + "|" + p.sequence() + "|" + p.offset();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Position decode(String cursor, String binding) {
        if (cursor == null) return Position.start();
        if (cursor.isBlank() || cursor.length() > 512) throw invalid();
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 7 || !"1".equals(fields[0]) || !binding.equals(fields[1])) throw invalid();
            Position p = new Position(Instant.parse(fields[2]), Integer.parseInt(fields[3]),
                    Long.parseLong(fields[4]), Long.parseLong(fields[5]), Integer.parseInt(fields[6]));
            if (p.at().isBefore(Instant.EPOCH) || p.at().isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                    || p.source() < 0 || p.source() > 1 || p.id() <= 0 || p.sequence() < 0
                    || (p.source() == 0 && p.sequence() != 0) || (p.source() == 1 && p.sequence() == 0)
                    || p.offset() < -1) throw invalid();
            return p;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    static String sanitize(String value) {
        if (value == null) return "";
        String safe = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED PRIVATE KEY]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = CREDENTIAL.matcher(safe).replaceAll("$1[REDACTED]");
        return KEY.matcher(safe).replaceAll("[REDACTED]");
    }

    private static int boundary(String text, int index) {
        return index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))
                && Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1 : index;
    }

    private static String bounded(String text, int max) {
        return text.substring(0, boundary(text, Math.min(max, text.length())));
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid history arguments"); }
    static final class UnavailableException extends RuntimeException { }
}
