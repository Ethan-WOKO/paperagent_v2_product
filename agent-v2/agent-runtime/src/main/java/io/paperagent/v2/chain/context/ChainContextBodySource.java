package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-ref body loader used by product projectors for mechanical expansion.
 * It must never reinterpret a historical request as a query for the latest body.
 */
@FunctionalInterface
public interface ChainContextBodySource {
    BodyPage load(BodyRequest request);

    /** Loads one exact authority body through every stable page. */
    default List<BodyItem> loadAll(BodyRequest initialRequest) {
        Objects.requireNonNull(initialRequest, "initialRequest");
        List<BodyItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BodyRequest request = initialRequest;
        String previousItemId = request.afterItemId();
        while (true) {
            BodyPage page = Objects.requireNonNull(
                    load(request), "body page").validateFor(request);
            for (BodyItem item : page.items()) {
                if ((previousItemId != null
                        && item.itemId().compareTo(previousItemId) <= 0)
                        || !seen.add(item.itemId())) {
                    throw invalid("body pages must advance with unique stable IDs");
                }
                result.add(item);
                previousItemId = item.itemId();
            }
            if (page.complete()) return List.copyOf(result);
            request = request.after(page.nextAfterItemId());
        }
    }

    record BodyRequest(
            String taskId,
            String contextRevisionId,
            ChainContextModule module,
            String authorityType,
            String authorityRef,
            String authorityVersion,
            String authorityDigest,
            String afterItemId,
            int pageSize) {
        public BodyRequest {
            taskId = required(taskId, "taskId");
            contextRevisionId = required(contextRevisionId, "contextRevisionId");
            Objects.requireNonNull(module, "module");
            authorityType = required(authorityType, "authorityType");
            authorityRef = required(authorityRef, "authorityRef");
            authorityVersion = required(authorityVersion, "authorityVersion");
            requireSha256(authorityDigest, "authorityDigest");
            if (afterItemId != null) {
                afterItemId = required(afterItemId, "afterItemId");
            }
            if (pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be positive");
            }
        }

        BodyRequest after(String cursor) {
            return new BodyRequest(taskId, contextRevisionId, module,
                    authorityType, authorityRef, authorityVersion,
                    authorityDigest, required(cursor, "cursor"), pageSize);
        }

        public BodyRequest withPageSize(int requestedPageSize) {
            return new BodyRequest(taskId, contextRevisionId, module,
                    authorityType, authorityRef, authorityVersion,
                    authorityDigest, afterItemId, requestedPageSize);
        }
    }

    record BodyItem(
            String itemId,
            String authorityVersion,
            String body,
            String bodySha256) {
        public BodyItem {
            itemId = required(itemId, "itemId");
            authorityVersion = required(authorityVersion, "authorityVersion");
            body = Objects.requireNonNull(body, "body");
            requireSha256(bodySha256, "bodySha256");
            if (!ChainContextDigests.sha256(body).equals(bodySha256)) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID,
                        "body item digest mismatch: " + itemId);
            }
        }
    }

    record BodyPage(List<BodyItem> items, String nextAfterItemId, boolean complete) {
        public BodyPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (nextAfterItemId != null) {
                nextAfterItemId = required(nextAfterItemId, "nextAfterItemId");
            }
            if (complete && nextAfterItemId != null) {
                throw invalid("a complete body page cannot expose a next cursor");
            }
            if (!complete && items.isEmpty()) {
                throw invalid("an incomplete body page must make progress");
            }
            if (!complete && !items.get(items.size() - 1).itemId().equals(nextAfterItemId)) {
                throw invalid("next cursor must equal the final item ID");
            }
            List<String> ids = items.stream().map(BodyItem::itemId).toList();
            List<String> sorted = ids.stream().sorted().toList();
            if (!ids.equals(sorted) || new HashSet<>(ids).size() != ids.size()) {
                throw invalid("body page items must use unique ascending stable IDs");
            }
        }

        public BodyPage validateFor(BodyRequest request) {
            Objects.requireNonNull(request, "request");
            if (items.size() > request.pageSize()) {
                throw invalid("body page exceeds the requested page size");
            }
            if (request.afterItemId() != null && items.stream()
                    .anyMatch(item -> item.itemId().compareTo(request.afterItemId()) <= 0)) {
                throw invalid("body page did not advance beyond the requested cursor");
            }
            return this;
        }
    }

    static <T> List<T> deterministicPage(
            List<T> values,
            java.util.function.Function<T, String> stableId,
            BodyRequest request) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(request, "request");
        List<T> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparing(value -> required(stableId.apply(value), "stableId")));
        Set<String> seen = new HashSet<>();
        for (T value : ordered) {
            if (!seen.add(stableId.apply(value))) {
                throw invalid("deterministic pagination requires unique stable IDs");
            }
        }
        return ordered.stream()
                .filter(value -> request.afterItemId() == null
                        || stableId.apply(value).compareTo(
                                request.afterItemId()) > 0)
                .limit(request.pageSize())
                .toList();
    }

    private static ChainContextException invalid(String message) {
        return new ChainContextException(ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID, message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireSha256(String value, String name) {
        required(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
            }
        }
    }
}
