package com.yanban.paper.web;

import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.service.PaperClarificationService;
import com.yanban.paper.service.PaperEventStreamService;
import com.yanban.paper.service.PaperOrchestrator;
import com.yanban.paper.service.PaperPreviewService;
import com.yanban.paper.service.PaperSectionService;
import com.yanban.paper.service.PaperTaskService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class PaperController {

    private final PaperTaskService paperTaskService;
    private final PaperEventStreamService paperEventStreamService;
    private final PaperOrchestrator paperOrchestrator;
    private final PaperClarificationService paperClarificationService;
    private final PaperSectionService paperSectionService;
    private final PaperPreviewService paperPreviewService;
    private final PaperTaskAnalysisRepository paperTaskAnalysisRepository;

    public PaperController(PaperTaskService paperTaskService,
                           PaperEventStreamService paperEventStreamService,
                           PaperOrchestrator paperOrchestrator,
                           PaperClarificationService paperClarificationService,
                           PaperSectionService paperSectionService,
                           PaperPreviewService paperPreviewService,
                           PaperTaskAnalysisRepository paperTaskAnalysisRepository) {
        this.paperTaskService = paperTaskService;
        this.paperEventStreamService = paperEventStreamService;
        this.paperOrchestrator = paperOrchestrator;
        this.paperClarificationService = paperClarificationService;
        this.paperSectionService = paperSectionService;
        this.paperPreviewService = paperPreviewService;
        this.paperTaskAnalysisRepository = paperTaskAnalysisRepository;
    }

    @PostMapping("/api/v1/paper/process")
    @ResponseStatus(HttpStatus.CREATED)
    public PaperTaskResponse process(@AuthenticationPrincipal(expression = "id") Long userId,
                                     @RequestHeader(value = "X-Client-Request-Id", required = false) String clientRequestId,
                                     @Valid @ModelAttribute PaperProcessRequest request) {
        return paperTaskService.createTask(userId, request, clientRequestId);
    }

    @GetMapping("/api/v1/paper/tasks")
    public List<PaperTaskHistoryResponse> tasks(@AuthenticationPrincipal(expression = "id") Long userId) {
        return paperTaskService.listTasks(userId);
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}")
    public PaperTaskResponse getTask(@AuthenticationPrincipal(expression = "id") Long userId,
                                     @PathVariable Long taskId) {
        return paperTaskService.getTask(userId, taskId);
    }

    @GetMapping("/api/v1/paper/events")
    public SseEmitter events(@AuthenticationPrincipal(expression = "id") Long userId,
                             @RequestParam Long taskId) {
        paperTaskService.getTask(userId, taskId);
        return paperEventStreamService.subscribe(taskId);
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void pause(@AuthenticationPrincipal(expression = "id") Long userId,
                      @PathVariable Long taskId) {
        paperOrchestrator.pause(userId, taskId);
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resume(@AuthenticationPrincipal(expression = "id") Long userId,
                       @PathVariable Long taskId) {
        paperOrchestrator.resume(userId, taskId);
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void stop(@AuthenticationPrincipal(expression = "id") Long userId,
                     @PathVariable Long taskId) {
        paperOrchestrator.stop(userId, taskId);
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/sections")
    public List<PaperSectionResponse> sections(@AuthenticationPrincipal(expression = "id") Long userId,
                                                @PathVariable Long taskId) {
        return paperSectionService.list(userId, taskId).stream()
                .map(PaperSectionResponse::from)
                .toList();
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/sections/{sectionId}/role")
    public PaperSectionResponse updateSectionRole(@AuthenticationPrincipal(expression = "id") Long userId,
                                                   @PathVariable Long taskId,
                                                   @PathVariable Long sectionId,
                                                   @Valid @RequestBody PaperSectionRoleUpdateRequest request) {
        return PaperSectionResponse.from(paperSectionService.updateRole(userId, taskId, sectionId, request.role()));
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/sections/{sectionId}/revision-status")
    public PaperSectionResponse updateSectionRevisionStatus(@AuthenticationPrincipal(expression = "id") Long userId,
                                                            @PathVariable Long taskId,
                                                            @PathVariable Long sectionId,
                                                            @Valid @RequestBody PaperSectionRevisionStatusUpdateRequest request) {
        return PaperSectionResponse.from(paperPreviewService.updateSectionRevisionStatus(userId, taskId, sectionId, request.status()));
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/sections/revision-status")
    public List<PaperSectionResponse> updateSectionRevisionStatuses(@AuthenticationPrincipal(expression = "id") Long userId,
                                                                    @PathVariable Long taskId,
                                                                    @Valid @RequestBody PaperSectionRevisionBatchUpdateRequest request) {
        return paperPreviewService.updateSectionRevisionStatuses(userId, taskId, request.sectionIds(), request.status()).stream()
                .map(PaperSectionResponse::from)
                .toList();
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/clarifications")
    public List<PaperClarificationResponse> clarifications(@AuthenticationPrincipal(expression = "id") Long userId,
                                                           @PathVariable Long taskId) {
        return paperClarificationService.list(userId, taskId).stream()
                .map(PaperClarificationResponse::from)
                .toList();
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/clarifications/{clarificationId}/answer")
    public PaperClarificationResponse answerClarification(@AuthenticationPrincipal(expression = "id") Long userId,
                                                          @PathVariable Long taskId,
                                                          @PathVariable Long clarificationId,
                                                          @Valid @RequestBody PaperClarificationAnswerRequest request) {
        return PaperClarificationResponse.from(
                paperClarificationService.answer(userId, taskId, clarificationId, request.answerJson())
        );
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/suggestions")
    public List<PaperSuggestionResponse> suggestions(@AuthenticationPrincipal(expression = "id") Long userId,
                                                     @PathVariable Long taskId) {
        return paperPreviewService.listSuggestions(userId, taskId);
    }

    @PostMapping("/api/v1/paper/tasks/{taskId}/suggestions/{suggestionId}/status")
    public PaperSuggestionResponse updateSuggestionStatus(@AuthenticationPrincipal(expression = "id") Long userId,
                                                          @PathVariable Long taskId,
                                                          @PathVariable Long suggestionId,
                                                          @Valid @RequestBody PaperSuggestionStatusUpdateRequest request) {
        return paperPreviewService.updateSuggestionStatus(userId, taskId, suggestionId, request.status());
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/analysis")
    public Map<String, String> analysis(@AuthenticationPrincipal(expression = "id") Long userId,
                                        @PathVariable Long taskId) {
        paperTaskService.getTask(userId, taskId);
        PaperTaskAnalysis analysis = paperTaskAnalysisRepository.findByTaskId(taskId).orElse(null);
        return Map.of(
                "researchProfileJson", analysis == null || analysis.getResearchProfileJson() == null ? "{}" : analysis.getResearchProfileJson(),
                "conceptLadderJson", analysis == null || analysis.getConceptLadderJson() == null ? "{}" : analysis.getConceptLadderJson(),
                "gapMatrixJson", analysis == null || analysis.getGapMatrixJson() == null ? "{}" : analysis.getGapMatrixJson()
        );
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/artifacts")
    public List<PaperArtifactResponse> artifacts(@AuthenticationPrincipal(expression = "id") Long userId,
                                                 @PathVariable Long taskId) {
        return paperPreviewService.listArtifacts(userId, taskId).stream()
                .map(PaperArtifactResponse::from)
                .toList();
    }

    @GetMapping("/api/v1/paper/tasks/{taskId}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal(expression = "id") Long userId,
                                             @PathVariable Long taskId) {
        Resource resource = paperTaskService.downloadResult(userId, taskId);
        String filename = paperTaskService.downloadFilename(userId, taskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(paperTaskService.downloadContentType(userId, taskId)))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(resource);
    }
}
