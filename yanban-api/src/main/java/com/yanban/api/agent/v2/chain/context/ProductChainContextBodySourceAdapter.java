package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.context.ChainContextBodySource;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact-reference body loader for chain-owned content plus stable product
 * authority loaders.
 *
 * <p>Chain content uses its immutable content ID as the authority version.
 * Product/project/evidence authorities are registered by explicit type and
 * retain responsibility for their own historical version lookup.</p>
 */
public final class ProductChainContextBodySourceAdapter
        implements ChainContextBodySource {
    private final ChainContextRepository contexts;
    private final ChainModelRepository chainContents;
    private final Map<String, ChainContextBodySource> authoritySources;

    public ProductChainContextBodySourceAdapter(
            ChainContextRepository contexts,
            ChainModelRepository chainContents,
            Map<String, ChainContextBodySource> authoritySources) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.chainContents = Objects.requireNonNull(
                chainContents, "chainContents");
        Objects.requireNonNull(authoritySources, "authoritySources");
        authoritySources.forEach((type, source) -> {
            required(type, "authority type");
            Objects.requireNonNull(source, "authority source");
            if (isChainContentAuthority(type)) {
                throw new IllegalArgumentException(
                        type + " authority is owned by the chain repository");
            }
        });
        this.authoritySources = Map.copyOf(authoritySources);
    }

    @Override
    public BodyPage load(BodyRequest request) {
        Objects.requireNonNull(request, "request");
        ChainRuntimePolicy policy = frozenPolicy(request);
        if (request.pageSize() > policy.contextBodyPageItemsMax()) {
            throw invalid("body page size exceeds the frozen runtime policy");
        }
        if (isChainContentAuthority(request.authorityType())) {
            return loadChainContent(request).validateFor(request);
        }
        ChainContextBodySource source = authoritySources.get(
                request.authorityType());
        if (source == null) {
            throw invalid("unknown body authority type");
        }
        BodyPage page = Objects.requireNonNull(
                source.load(request), "body page").validateFor(request);
        if (page.items().stream().anyMatch(item ->
                !item.authorityVersion().equals(
                        request.authorityVersion()))) {
            throw invalid("body page authority version mismatch");
        }
        return page;
    }

    @Override
    public List<BodyItem> loadAll(BodyRequest initialRequest) {
        Objects.requireNonNull(initialRequest, "initialRequest");
        ChainRuntimePolicy policy = frozenPolicy(initialRequest);
        return ChainContextBodySource.super.loadAll(initialRequest
                .withPageSize(policy.contextBodyPageItemsMax()));
    }

    private ChainRuntimePolicy frozenPolicy(BodyRequest request) {
        var revision = contexts.findContextRevision(
                        request.contextRevisionId())
                .orElseThrow(() -> invalid("context revision not found"));
        if (!revision.taskId().equals(request.taskId())) {
            throw invalid("context revision belongs to a different task");
        }
        try {
            return ChainRuntimePolicy.requireVersion(
                    revision.runtimePolicyVersion());
        } catch (IllegalArgumentException unsupportedPolicy) {
            throw invalid("context runtime policy is unsupported");
        }
    }

    private static boolean isChainContentAuthority(String authorityType) {
        for (ChainContentKind kind : ChainContentKind.values()) {
            if (kind.name().equals(authorityType)) {
                return true;
            }
        }
        return false;
    }

    private BodyPage loadChainContent(BodyRequest request) {
        ContentRecord content = chainContents.findContent(
                        request.authorityRef())
                .orElseThrow(() -> invalid("chain content not found"));
        if (!content.taskId().equals(request.taskId())) {
            throw invalid("chain content belongs to a different task");
        }
        if (!content.contentId().equals(request.authorityVersion())) {
            throw invalid("chain content version mismatch");
        }
        if (!content.bodySha256().equals(request.authorityDigest())) {
            throw invalid("chain content digest mismatch");
        }
        if (!content.contentKind().name().equals(
                request.authorityType())) {
            throw invalid("chain content authority type mismatch");
        }
        if (request.afterItemId() != null
                && content.contentId().compareTo(request.afterItemId()) <= 0) {
            return new BodyPage(List.of(), null, true);
        }
        return new BodyPage(List.of(new BodyItem(
                content.contentId(), content.contentId(), content.body(),
                content.bodySha256())), null, true);
    }

    private static ChainContextException invalid(String message) {
        return new ChainContextException(
                ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID, message);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
