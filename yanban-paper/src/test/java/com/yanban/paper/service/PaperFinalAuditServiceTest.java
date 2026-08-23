package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaperFinalAuditServiceTest {

    private final PaperFinalAuditService service = new PaperFinalAuditService();

    @Test
    void passesMergedCitationsAndEnclosedRecommendedEntry() {
        String tex = "Claim \\cite{existing,yb_new}.\\n\\label{eq:a} Equation~\\eqref{eq:a}.";
        String bib = """
                @article{existing,title={Existing}}
                % === YANBAN_RECOMMENDED_BEGIN ===
                % bibKey=yb_new suggestionId=1 cardId=2
                @article{yb_new,title={New}}
                % === YANBAN_RECOMMENDED_END ===
                """;

        PaperFinalAuditService.AuditResult result = service.audit(tex, bib, citationResult());

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.issues()).extracting(PaperFinalAuditService.AuditIssue::code)
                .contains("COMPILATION_NOT_EXECUTED");
    }

    @Test
    void reportsReferencedGraphicsMissingFromUploadedResources() {
        PaperFinalAuditService.AuditResult result = service.audit(
                "\\includegraphics{figures/system}", "", null, Set.of("main.tex"));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.issues()).extracting(PaperFinalAuditService.AuditIssue::code)
                .contains("MISSING_GRAPHICS_RESOURCE", "COMPILATION_NOT_EXECUTED");
    }

    @Test
    void failsMissingKeysResidualSlotsAndEmptyMarkerBlocks() {
        String tex = "Claim \\cite{missing} \\yanbancitationslot{1}.";
        String bib = "% === YANBAN_RECOMMENDED_BEGIN ===\\n% metadata only\\n% === YANBAN_RECOMMENDED_END ===\\n";

        PaperFinalAuditService.AuditResult result = service.audit(tex, bib, null);

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.issues()).extracting(PaperFinalAuditService.AuditIssue::code)
                .contains("MISSING_BIBLIOGRAPHY_KEY", "RESIDUAL_CITATION_SLOT", "EMPTY_RECOMMENDED_BIB_BLOCK");
    }

    private PaperCitationApplyService.CitationApplyResult citationResult() {
        return new PaperCitationApplyService.CitationApplyResult(
                "", "", "", List.of(), List.of(),
                List.of(Map.of("suggestionId", 1L, "bibKeys", List.of("yb_new"))),
                List.of(), 1, Set.of("yb_new"), "refs.bib");
    }
}
