package com.yanban.api.attachment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yanban.core.agent.*;
import com.yanban.core.model.*;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.service.*;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.web.KbDocumentResponse;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class SessionAttachmentServiceTest {
    SessionAttachmentRepository repository=mock(SessionAttachmentRepository.class);
    AttachmentSnapshotRepository snapshots=mock(AttachmentSnapshotRepository.class);
    Map<String,AttachmentSnapshot> frozen=new HashMap<>();
    AgentSessionRepository sessions=mock(AgentSessionRepository.class);
    AttachmentStorage storage=mock(AttachmentStorage.class);
    KnowledgeUploadService knowledge=mock(KnowledgeUploadService.class);
    List<SessionAttachment> rows=new ArrayList<>();
    SessionAttachmentService service;
    @BeforeEach void setup() {
        when(snapshots.findById(anyString())).thenAnswer(call->Optional.ofNullable(frozen.get(call.getArgument(0))));
        when(snapshots.saveAndFlush(any())).thenAnswer(call->{ AttachmentSnapshot row=call.getArgument(0); frozen.put(row.id,row); return row; });
        when(sessions.findByIdAndUserId(2L,1L)).thenReturn(Optional.of(new AgentSession(1L,"test","custom","vision",4,false)));
        when(repository.findByUserIdAndSessionIdAndActiveTrueOrderByIdAsc(anyLong(),anyLong())).thenAnswer(call -> rows.stream()
                .filter(a->a.userId.equals(call.getArgument(0)) && a.sessionId.equals(call.getArgument(1)) && a.active).toList());
        when(repository.save(any())).thenAnswer(call->{ SessionAttachment a=call.getArgument(0); if(a.id==null) { a.id=(long)rows.size()+1;rows.add(a); } return a; });
        when(repository.findById(anyLong())).thenAnswer(call->rows.stream().filter(a->a.id.equals(call.getArgument(0))).findFirst());
        service=new SessionAttachmentService(repository,sessions,snapshots,mock(EntityManager.class),storage,new AttachmentParser(),
                new AttachmentVisionPolicy("custom:vision"),knowledge,new KnowledgeResourceLimiter(new KnowledgeUploadProperties()),mock(UserAccountPolicy.class),true);
    }
    SessionAttachment.View upload(String text) {
        return service.upload(1L,2L,new MockMultipartFile("file","notes.txt","text/plain",text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    ChatRequest request(String model) { return new ChatRequest("custom",model,List.of(ChatMessage.user("请总结")),null,null,null,"key"); }
    @Test void uploadDoesNotIngestKnowledgeAndFollowupGetsActualText() throws Exception {
        var a=upload("合同总价为 123 元");
        assertThat(a.status()).isEqualTo("READY");
        verifyNoInteractions(knowledge);
        verify(storage).put(startsWith("session-attachments/1/2/"),eq("text/plain"),any());
        assertThat(service.list(1L,2L)).hasSize(1);
        assertThat(service.enrich(request("text"),1L,2L,"turn:1").messages().get(0).content()).contains("123 元","notes.txt");
        service.bindMessage(1L,2L,77L);
        service.bindMessage(1L,2L,78L);
        assertThat(service.list(1L,2L).get(0).firstMessageId()).isEqualTo(77L);
        assertThat(service.enrich(request("text"),1L,2L,"turn:1").messages().get(0).content()).contains("123 元");
    }
    @Test void failedDocumentIsVisibleAndBlocksSendWithoutIndexing() {
        var a=upload("x".repeat(AttachmentParser.MAX_TEXT+1));
        assertThat(a.status()).isEqualTo("FAILED");
        assertThat(a.errorMessage()).contains("24000");
        assertThatThrownBy(()->service.validateForSend(1L,2L,"custom","text")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage,knowledge);
    }
    @Test void parserFailureDoesNotDiscloseInfrastructureDetails() throws Exception {
        doThrow(new java.io.IOException("secret credential")).when(storage).put(anyString(),anyString(),any());
        assertThat(upload("hello").errorMessage()).doesNotContain("secret");
    }
    @Test void foreignSessionAndCrossSessionIdAreDeniedBeforeStorage() {
        var a=upload("owned");
        assertThatThrownBy(()->service.list(9L,2L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.remove(9L,2L,a.id())).isInstanceOf(ResponseStatusException.class);
        when(sessions.findByIdAndUserId(3L,1L)).thenReturn(Optional.of(new AgentSession(1L,"other","custom","text",4,false)));
        assertThatThrownBy(()->service.promote(1L,3L,a.id())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(knowledge);
    }
    @Test void removalStopsFutureContextButPreservesRecord() {
        var a=upload("keep");service.remove(1L,2L,a.id());
        assertThat(service.list(1L,2L)).isEmpty();
        var request=request("text");assertThat(service.enrich(request,1L,2L,"turn:2")).isSameAs(request);
        assertThat(rows.get(0).extractedText).isEqualTo("keep");
    }
    @Test void promotionIsExplicitAndRepeatedRequestDoesNotCreateSecondDocument() throws Exception {
        var a=upload("original");when(storage.read(anyString())).thenReturn("original".getBytes());
        var document=mock(KbDocumentResponse.class);when(document.id()).thenReturn(99L);
        when(knowledge.mergeChunks(eq(1L),any())).thenReturn(document);
        assertThat(service.promote(1L,2L,a.id())).isEqualTo(99L);
        assertThat(service.promote(1L,2L,a.id())).isEqualTo(99L);
        verify(knowledge,times(1)).uploadChunk(eq(1L),any());
        verify(knowledge,times(1)).mergeChunks(eq(1L),any());
    }
    @Test void imageRequiresExplicitCapabilityAndIncludesPixelsWhenEnabled() throws Exception {
        var a=new SessionAttachment(1L,2L,"image.png","image/png","private",3);
        a.status="READY";repository.save(a);when(storage.read("private")).thenReturn(new byte[]{1,2,3});
        assertThatThrownBy(()->service.validateForSend(1L,2L,"custom","text")).hasMessageContaining("图片理解");
        var message=service.enrich(request("vision"),1L,2L,"turn:1").messages().get(0);
        assertThat(message.images()).containsExactly(new ChatImage("image/png","AQID"));
        assertThat(message.images().get(0).toString()).doesNotContain("AQID");
    }
    @Test void totalDocumentBudgetIsNotSilentlyTruncated() {
        upload("a".repeat(24000));upload("b".repeat(24000));
        assertThat(upload("third").status()).isEqualTo("FAILED");
    }
    @Test void projectUploadCannotBypassProjectRuntime() {
        when(sessions.findByIdAndUserId(2L,1L)).thenReturn(Optional.of(new AgentSession(1L,"project","custom","vision",4,false,AgentSessionScope.PROJECT,8L)));
        assertThatThrownBy(()->upload("text")).hasMessageContaining("工作区");verifyNoInteractions(storage,knowledge);
    }
    @Test void actualWorkspaceAdapterDeliversAttachmentOnBothChatAndStreaming() {
        upload("真实文档内容");
        var provider=mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("done"),"stop",null));
        when(provider.streamChat(any())).thenReturn(reactor.core.publisher.Flux.just(ChatChunk.token("done"),ChatChunk.done("stop")));
        var adapter=new com.yanban.api.agent.LangChain4jChatModelAdapter(provider,new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"attachments",service);
        var runtime=new com.yanban.api.agent.AgentRuntimeRequest(null,2L,List.of(),1L,"summarize","custom","text",
                null,null,4,true,null,"key",null,null,com.yanban.api.agent.AgentRuntimeMode.LANGCHAIN4J,
                com.yanban.api.agent.AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,List.of(),null,null,"trace",null,null);
        var input=dev.langchain4j.model.chat.request.ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("summarize"))
                .parameters(dev.langchain4j.model.chat.request.ChatRequestParameters.builder().modelName("text").build()).build();
        adapter.chat(input,runtime);adapter.stream(input,runtime).collectList().block();
        var captor=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).chat(captor.capture());verify(provider).streamChat(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request->assertThat(request.messages().get(0).content()).contains("真实文档内容"));
    }
    @Test void inFlightTurnAndPlanSelectionStayFrozenAcrossRemovalAndUploads() {
        var first=upload("first file");
        service.enrich(request("text"),1L,2L,"plan:15:step:1");
        service.remove(1L,2L,first.id());upload("new file");
        assertThat(service.enrich(request("text"),1L,2L,"plan:15:step:2").messages().get(0).content())
                .contains("first file").doesNotContain("new file");
        assertThat(service.enrich(request("text"),1L,2L,"turn:99").messages().get(0).content())
                .contains("new file").doesNotContain("first file");
    }
    @Test void plainTextRequestIsUnchangedWithoutAttachments() {
        var request=request("text");assertThat(service.enrich(request,1L,2L,"turn:2")).isSameAs(request);
    }
}
