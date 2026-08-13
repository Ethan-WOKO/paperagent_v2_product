package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextBodySource;
import io.paperagent.v2.chain.context.ChainContextBodySource.BodyPage;
import io.paperagent.v2.chain.context.ChainContextBodySource.BodyRequest;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainContextRevisionRecoveryTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void productSourceAlwaysProjectsTheCompleteFrozenOrdinalSet() {
        List<ChainContextModule> calls = new ArrayList<>();
        List<ProductChainContextModuleSource> sources = new ArrayList<>();
        for (ChainContextModule module : ChainContextModule.values()) {
            sources.add(new ProductChainContextModuleSource(module,
                    request -> {
                        calls.add(module);
                        var version = ChainContextVersionMatrix.requirement(
                                module);
                        return new ProductChainContextAuthorityProjection(
                                ChainContextModuleStatus.PRESENT,
                                values(version.sourceVersionFields(),
                                        module, "source"),
                                values(version.readBoundaryFields(),
                                        module, "boundary"),
                                "product-projector-v1", "stable-id-v1",
                                Map.of("pageSize",
                                        ChainContextValue.number(25)),
                                values(request.requiredFields(module),
                                        module, "field"),
                                null);
                    }));
        }
        Collections.reverse(sources);
        ProductChainContextSourceAdapter adapter =
                new ProductChainContextSourceAdapter(List.copyOf(sources));

        List<ChainContextSourceSnapshot> result = adapter.project(
                new ChainContextProjectionRequest(buildingRevision(),
                        100_000));

        assertThat(result).extracting(ChainContextSourceSnapshot::module)
                .containsExactly(ChainContextModule.values());
        assertThat(calls).containsExactly(ChainContextModule.values());
        assertThat(result).allSatisfy(snapshot -> {
            assertThat(snapshot.projectionFieldNames()).containsExactlyInAnyOrderElementsOf(
                    new ChainContextProjectionRequest(buildingRevision(),
                            100_000).requiredFields(snapshot.module()));
            assertThat(snapshot.visibleSourceRefs()).isNotEmpty();
        });
        assertThatThrownBy(() -> new ProductChainContextSourceAdapter(
                List.copyOf(sources.subList(0, 12))))
                .isInstanceOfSatisfying(ChainContextException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                ChainContextErrorCode.CONTEXT_SOURCE_MODULE_MISSING));
    }

    @Test
    void productBodyLoaderReadsOnlyTheExactChainContentIdentity() {
        String body = "BODY-CANARY-authoritative-content";
        ContentRecord content = new ContentRecord(
                "content.1", "task.1", "invocation.1",
                ChainContentKind.ANSWER_BODY, body, sha256(body),
                "text/plain", CREATED_AT);
        ChainModelRepository repository = mock(ChainModelRepository.class);
        ChainContextRepository contexts = mock(ChainContextRepository.class);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(buildingRevision()));
        when(repository.findContent("content.1"))
                .thenReturn(Optional.of(content));
        ProductChainContextBodySourceAdapter adapter =
                new ProductChainContextBodySourceAdapter(
                        contexts, repository, Map.of());
        BodyRequest request = new BodyRequest(
                "task.1", "context.1",
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                ChainContentKind.ANSWER_BODY.name(), "content.1",
                "content.1", sha256(body), null, 1);

        BodyPage page = adapter.load(request);

        assertThat(page.complete()).isTrue();
        assertThat(page.nextAfterItemId()).isNull();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("content.1");
            assertThat(item.authorityVersion()).isEqualTo("content.1");
            assertThat(item.body()).isEqualTo(body);
            assertThat(item.bodySha256()).isEqualTo(sha256(body));
        });
        assertThat(adapter.load(new BodyRequest(
                "task.1", "context.1",
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                ChainContentKind.ANSWER_BODY.name(), "content.1",
                "content.1", sha256(body), "content.1", 1)).items())
                .isEmpty();

        assertThatThrownBy(() -> adapter.load(new BodyRequest(
                "task.other", "context.1",
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                ChainContentKind.ANSWER_BODY.name(), "content.1",
                "content.1", sha256(body), null, 1)))
                .isInstanceOfSatisfying(ChainContextException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID));

        assertThatThrownBy(() -> adapter.load(new BodyRequest(
                "task.1", "context.1",
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                ChainContentKind.WORKSPACE_CHANGE_BODY.name(), "content.1",
                "content.1", sha256(body), null, 1)))
                .isInstanceOfSatisfying(ChainContextException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID));
    }

    @Test
    void productBodyLoaderRejectsExternalVersionFallback() {
        ChainContextRepository contexts = mock(ChainContextRepository.class);
        ChainModelRepository repository = mock(ChainModelRepository.class);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(buildingRevision()));
        ChainContextBodySource external = request -> new BodyPage(
                List.of(new ChainContextBodySource.BodyItem(
                        "item.1", "latest-version", "body", sha256("body"))),
                null, true);
        ProductChainContextBodySourceAdapter adapter =
                new ProductChainContextBodySourceAdapter(
                        contexts, repository, Map.of("PROJECT_FILE", external));

        assertThatThrownBy(() -> adapter.load(new BodyRequest(
                "task.1", "context.1",
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "PROJECT_FILE", "file.1", "frozen-version",
                sha256("body"), null, 25)))
                .isInstanceOfSatisfying(ChainContextException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID));
    }

    private static ContextRevisionRecord buildingRevision() {
        return new ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "advance-step",
                "instruction.1", "task-frame.1", "plan.1",
                "revision.1", 1L, "step.1", "activation.1",
                41L, "project-version.1", "workspace.1", null,
                null, null, null, null, "product-projectors-v1",
                "stable-id-v1",
                io.paperagent.v2.chain.ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, CREATED_AT, null);
    }

    private static Map<String, ChainContextValue> values(
            List<String> names,
            ChainContextModule module,
            String group) {
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        for (String name : names) {
            values.put(name, ChainContextValue.referencedText(
                    group + "-value-" + name,
                    module.wireName() + ":" + group + ":" + name));
        }
        return Map.copyOf(values);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte element : digest) {
                output.append(String.format("%02x", element & 0xff));
            }
            return output.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
