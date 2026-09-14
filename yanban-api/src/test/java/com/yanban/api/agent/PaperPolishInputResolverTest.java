package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectService;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.paper.domain.*;
import com.yanban.paper.service.PaperStorageService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class PaperPolishInputResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PaperTaskRepository tasks = mock(PaperTaskRepository.class);
    private final PaperTaskArtifactRepository artifacts = mock(PaperTaskArtifactRepository.class);
    private final PaperStorageService storage = mock(PaperStorageService.class);
    private final KbDocumentRepository documents = mock(KbDocumentRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final PaperPolishInputResolver resolver = new PaperPolishInputResolver(tasks, artifacts, storage,
            documents, mock(ObjectProvider.class), new KnowledgeStorageProperties(), projects);

    @Test void acceptsOriginalOwnedTaskWithBoundedReads() throws Exception {
        PaperTask task = new PaperTask(7L, "owned", "main.tex", "owned.tex", "COMPLETED", "en", "DONE", null);
        when(tasks.findByIdAndUserId(23L, 7L)).thenReturn(Optional.of(task));
        when(storage.read("owned.tex", PaperPolishInputResolver.MAX_BYTES)).thenReturn(bytes());
        var source = resolver.resolve(7L, null, resolver.validate(args().put("sourceTaskId", 23)));
        assertThat(source.tex().getOriginalFilename()).isEqualTo("main.tex");
        assertThat(source.tex().getBytes()).isEqualTo(bytes());
        verifyNoInteractions(documents, projects);
    }

    @Test void rejectsTaskIdUsedAsDocumentAndDocumentUsedAsTask() {
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("documentId", 23)))).hasMessageContaining("上传文档");
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("sourceTaskId", 24)))).hasMessageContaining("论文任务");
        verifyNoInteractions(storage);
    }

    @Test void rejectsForeignPublicDocumentAndNonUploadDerivedMaterial() {
        assertThatThrownBy(() -> resolver.resolve(8L, null, resolver.validate(args().put("documentId", 23)))).hasMessageContaining("不可访问");
        KbDocument generated = new KbDocument(7L, "main.tex", "READY", true);
        generated.setObjectKey("artifact.tex"); generated.setSourceType("PAPER_ARTIFACT");
        when(documents.findByIdAndUserId(23L, 7L)).thenReturn(Optional.of(generated));
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("documentId", 23)))).hasMessageContaining("上传原文件");
    }

    @Test void rejectsUnsupportedDocumentRoleBeforeStorage() {
        KbDocument pdf = new KbDocument(7L, "main.pdf", "READY", false); pdf.setObjectKey("owned.pdf");
        when(documents.findByIdAndUserId(23L, 7L)).thenReturn(Optional.of(pdf));
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("documentId", 23)))).hasMessageContaining(".tex");
        verifyNoInteractions(storage);
    }

    @Test void projectSourceUsesExactVersionAndHashWithoutProjectWrites() throws Exception {
        var cut = mock(ProjectService.ProjectVersionByteMaterialization.class, RETURNS_DEEP_STUBS);
        when(cut.snapshot().workspace().projectVersion().value()).thenReturn("v-exact");
        when(cut.files()).thenReturn(Map.of("paper/main.tex", bytes()));
        when(projects.materializeVersionBytes(7L, 9L, Set.of("paper/main.tex"))).thenReturn(cut);
        var source = resolver.resolve(7L, 9L, resolver.validate(projectArgs()));
        assertThat(source.tex().getBytes()).isEqualTo(bytes());
        verify(projects).manifest(7L, 9L);
        verify(projects).materializeVersionBytes(7L, 9L, Set.of("paper/main.tex"));
        verifyNoMoreInteractions(projects);
    }

    @Test void rejectsProjectDigestMismatchAndVersionDrift() {
        var cut = mock(ProjectService.ProjectVersionByteMaterialization.class, RETURNS_DEEP_STUBS);
        when(cut.snapshot().workspace().projectVersion().value()).thenReturn("v-exact");
        when(cut.files()).thenReturn(Map.of("paper/main.tex", "changed".getBytes(StandardCharsets.UTF_8)));
        when(projects.materializeVersionBytes(anyLong(), anyLong(), anySet())).thenReturn(cut);
        assertThatThrownBy(() -> resolver.resolve(7L, 9L, resolver.validate(projectArgs()))).hasMessageContaining("哈希");
        when(cut.snapshot().workspace().projectVersion().value()).thenReturn("v-new");
        assertThatThrownBy(() -> resolver.resolve(7L, 9L, resolver.validate(projectArgs()))).hasMessageContaining("项目版本");
    }

    @Test void projectPathsRequireAuthenticatedProjectAndNormalizedPath() {
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(projectArgs()))).hasMessageContaining("项目对话");
        assertThatThrownBy(() -> resolver.validate(projectArgs().put("projectPath", "../main.tex"))).hasMessageContaining("相对路径");
        assertThatThrownBy(() -> resolver.validate(projectArgs().put("projectPath", "C:/main.tex"))).hasMessageContaining("相对路径");
        verifyNoInteractions(projects);
    }

    @Test void missingAmbiguousAndForgedParametersAreRejected() {
        assertThatThrownBy(() -> resolver.validate(args())).hasMessageContaining("只能选择一个");
        assertThatThrownBy(() -> resolver.validate(args().put("sourceTaskId", 1).put("documentId", 2))).hasMessageContaining("只能选择一个");
        assertThatThrownBy(() -> resolver.validate(args().put("sourceTaskId", "1"))).hasMessageContaining("正整数");
        assertThatThrownBy(() -> resolver.validate(args().put("latexText", PaperPolishStartServiceTest.TEX).put("userId", 8))).hasMessageContaining("不支持");
        assertThatThrownBy(() -> resolver.validate(args().put("latexText", PaperPolishStartServiceTest.TEX).put("clientRequestId", "model-key"))).hasMessageContaining("不支持");
    }

    @Test void rejectsCrossRoleBibliographyAndPlainText() {
        assertThatThrownBy(() -> resolver.validate(args().put("sourceTaskId", 1).put("bibDocumentId", 2))).hasMessageContaining("同一类型");
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("latexText", "just a paragraph")))).hasMessageContaining("完整 LaTeX");
    }

    @Test void rejectsOversizedUtf8AndInvalidLanguage() {
        assertThatThrownBy(() -> resolver.resolve(7L, null, resolver.validate(args().put("latexText", "中".repeat(400_000))))).hasMessageContaining("1 MiB");
        assertThatThrownBy(() -> resolver.validate(args().put("latexText", PaperPolishStartServiceTest.TEX).put("targetLanguage", "fr"))).hasMessageContaining("zh 或 en");
    }

    @Test void replayAuthorizationDoesNotMaterializeOrReadFrozenBytes() {
        resolver.authorize(7L, 9L, resolver.validate(projectArgs()));
        verify(projects).manifest(7L, 9L);
        verifyNoMoreInteractions(projects);
        verifyNoInteractions(storage);
    }

    private ObjectNode args() { return json.createObjectNode().put("targetLanguage", "en"); }
    private ObjectNode projectArgs() { return args().put("projectPath", "paper/main.tex")
            .put("expectedSha256", PaperPolishStartService.digest(bytes())).put("expectedProjectVersion", "v-exact"); }
    private byte[] bytes() { return PaperPolishStartServiceTest.TEX.getBytes(StandardCharsets.UTF_8); }
}
