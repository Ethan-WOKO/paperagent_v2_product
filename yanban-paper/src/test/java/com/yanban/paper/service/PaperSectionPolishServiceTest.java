package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = PaperSectionPolishServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperSectionPolishServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperSection.class, PaperTaskAnalysis.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperSectionRepository.class, PaperTaskAnalysisRepository.class})
    @Import({PaperSectionPolishService.class, PaperPromptService.class, LatexMaskingService.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SequencedModelClient paperModelClient() {
            return new SequencedModelClient();
        }

        @Bean
        PaperStorageService paperStorageService() {
            return new PaperStorageService(new EmptyMinioProvider(), new com.yanban.paper.config.PaperStorageProperties()) {
                @Override
                public String storeArtifact(Long userId, String type, String filename, byte[] bytes, String contentType) {
                    return "test://" + type + "/" + filename;
                }
            };
        }
    }

    private final PaperSectionPolishService polishService;
    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskAnalysisRepository analyses;
    private final SequencedModelClient modelClient;

    @Autowired
    PaperSectionPolishServiceTest(PaperSectionPolishService polishService,
                                  PaperTaskRepository tasks,
                                  PaperSectionRepository sections,
                                  PaperTaskAnalysisRepository analyses,
                                  SequencedModelClient modelClient) {
        this.polishService = polishService;
        this.tasks = tasks;
        this.sections = sections;
        this.analyses = analyses;
        this.modelClient = modelClient;
    }

    @BeforeEach
    void resetModelClient() {
        modelClient.reset();
    }

    @Test
    void structuralCommandChangeIsRejectedAndOriginalIsKept() {
        modelClient.returnExtraLabel = true;
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1);

        assertThat(result.status()).isEqualTo("PROTECTION_REJECTED");
        assertThat(result.polishedText()).isEqualTo(latexSection.rawText());
    }

    @Test
    void droppedPlaceholderIsRejectedAndLowScoreTriggersRetry() {
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        analyses.save(new PaperTaskAnalysis(task.getId()));
        PaperSection stored = sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG \\cite{rag2020}.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 3);

        assertThat(result.status()).isEqualTo("POLISHED");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.reviewScore()).isEqualTo(88);
        assertThat(result.polishedText()).contains("\\cite{rag2020}");
        assertThat(modelClient.userPrompts.get(1)).contains("Missing placeholders", "[[YANBAN_CITE_0001]]");
        PaperSection saved = sections.findById(stored.getId()).orElseThrow();
        assertThat(saved.getPolishStatus()).isEqualTo("POLISHED");
        assertThat(saved.getReviewJson()).contains("88", "attempts", "reasonCode", "maxRounds", "innerMaxAttempts");
        assertThat(saved.getDiffJson()).contains("schemaVersion", "hunks", "unifiedDiff", "originalWordCount");
    }

    @Test
    void repeatedPlaceholderFailureKeepsOriginalSection() {
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG \\cite{rag2020}.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1);

        assertThat(result.status()).isEqualTo("PROTECTION_REJECTED");
        assertThat(result.polishedText()).isEqualTo(latexSection.rawText());
    }

    @Test
    void scoreThresholdUsesZeroToOneHundredScale() {
        modelClient.preserveCitationOnFirstPolish = true;
        modelClient.alwaysPassReview = true;
        modelClient.finalReviewScore = 88;
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        PaperSection stored = sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG \\cite{rag2020}.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 90, 1, 1);

        assertThat(result.status()).isEqualTo("REVIEW_FAILED");
        assertThat(result.reviewScore()).isEqualTo(88);
        PaperSection saved = sections.findById(stored.getId()).orElseThrow();
        assertThat(saved.getReviewJson()).contains("\"reasonCode\":\"review_failed\"", "\"keptOriginal\":true");
    }

    @Test
    void ratioReviewScoreIsRejectedInsteadOfScaled() {
        modelClient.preserveCitationOnFirstPolish = true;
        modelClient.returnRatioScore = true;
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        PaperSection stored = sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG \\cite{rag2020}.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1, 1);

        assertThat(result.status()).isEqualTo("REVIEW_FAILED");
        assertThat(result.reviewScore()).isEqualTo(0.88);
        PaperSection saved = sections.findById(stored.getId()).orElseThrow();
        assertThat(saved.getReviewJson()).contains("review_score_scale_invalid", "\"score\":0.88");
    }

    @Test
    void modelFailureKeepsOriginalAndRecordsReason() {
        modelClient.failPolishCall = true;
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        PaperSection stored = sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 3, 2);

        assertThat(result.status()).isEqualTo("MODEL_FAILED");
        assertThat(result.polishedText()).isEqualTo(latexSection.rawText());
        PaperSection saved = sections.findById(stored.getId()).orElseThrow();
        assertThat(saved.getReviewJson()).contains("model_call_failed", "\"keptOriginal\":true");
    }

    @Test
    void inheritedDocumentBoundaryDoesNotRejectConclusionPolish() {
        modelClient.alwaysPassReview = true;
        modelClient.customPolishOutput = "\\section{CONCLUSION}\nThe revised conclusion is clearer.\n"
                + "\\bibliographystyle{IEEEtran}\n\\bibliography{refs}\n\\end{document}";
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "CONCLUSION", "CONCLUSION", 1.0, "test", 0, 120));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "CONCLUSION", LatexSectionRole.CONCLUSION, 0, 120,
                "\\section{CONCLUSION}\nThe original conclusion.\n"
                        + "\\bibliographystyle{IEEEtran}\n\\bibliography{refs}\n\\end{document}");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1, 1);

        assertThat(result.status()).isEqualTo("POLISHED");
        assertThat(result.polishedText()).contains("The revised conclusion is clearer.", "\\end{document}");
        assertThat(result.lintIssues()).isEmpty();
    }

    @Test
    void whitespaceOnlyCandidateIsRecordedAsUnchanged() {
        modelClient.customPolishOutput = "\\section{Introduction}\n\nThis   paper uses RAG.";
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        PaperSection stored = sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 80,
                "\\section{Introduction}\nThis paper uses RAG.");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1, 1);

        assertThat(result.status()).isEqualTo("UNCHANGED");
        assertThat(result.polishedText()).isEqualTo(latexSection.rawText());
        assertThat(sections.findById(stored.getId()).orElseThrow().getReviewJson())
                .contains("no_substantive_change");
    }

    @Test
    void newlyIntroducedFragmentLintBlockerIsStillRejected() {
        modelClient.alwaysPassReview = true;
        modelClient.customPolishOutput = "\\section{CONCLUSION}\nThe revised conclusion.\\begin{quote}\n"
                + "\\bibliographystyle{IEEEtran}\n\\bibliography{refs}\n\\end{document}";
        PaperTask task = tasks.save(new PaperTask(1L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "POLISH", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "CONCLUSION", "CONCLUSION", 1.0, "test", 0, 120));
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "CONCLUSION", LatexSectionRole.CONCLUSION, 0, 120,
                "\\section{CONCLUSION}\nThe original conclusion.\n"
                        + "\\bibliographystyle{IEEEtran}\n\\bibliography{refs}\n\\end{document}");

        SectionPolishResult result = polishService.polishSection(task.getId(), latexSection, "en", 80, 1, 1);

        assertThat(result.status()).isEqualTo("PROTECTION_REJECTED");
        assertThat(result.polishedText()).isEqualTo(latexSection.rawText());
    }

    static final class EmptyMinioProvider implements ObjectProvider<io.minio.MinioClient> {
        @Override
        public io.minio.MinioClient getObject(Object... args) {
            return null;
        }

        @Override
        public io.minio.MinioClient getIfAvailable() {
            return null;
        }

        @Override
        public io.minio.MinioClient getIfUnique() {
            return null;
        }

        @Override
        public io.minio.MinioClient getObject() {
            return null;
        }
    }

    static final class SequencedModelClient implements PaperModelClient {
        private int polishCalls;
        private int reviewCalls;
        private boolean returnExtraLabel;
        private boolean preserveCitationOnFirstPolish;
        private boolean alwaysPassReview;
        private boolean returnRatioScore;
        private boolean failPolishCall;
        private String customPolishOutput;
        private final java.util.List<String> userPrompts = new java.util.ArrayList<>();
        private int finalReviewScore = 88;

        void reset() {
            polishCalls = 0;
            reviewCalls = 0;
            returnExtraLabel = false;
            preserveCitationOnFirstPolish = false;
            alwaysPassReview = false;
            returnRatioScore = false;
            failPolishCall = false;
            customPolishOutput = null;
            userPrompts.clear();
            finalReviewScore = 88;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {
            userPrompts.add(userPrompt);
            if (userPrompt.contains("Return strict JSON only")) {
                reviewCalls++;
                if (returnRatioScore) {
                    return "{\"score\":0.88,\"passed\":true,\"issues\":[],\"suggestions\":[]}";
                }
                if (alwaysPassReview) {
                    return "{\"score\":" + finalReviewScore + ",\"passed\":true,\"issues\":[],\"suggestions\":[]}";
                }
                if (reviewCalls == 1) {
                    return "{\"score\":60,\"passed\":false,\"issues\":[{\"severity\":\"major\",\"message\":\"Too vague\"}],\"suggestions\":[\"Be specific\"]}";
                }
                return "{\"score\":" + finalReviewScore + ",\"passed\":true,\"issues\":[],\"suggestions\":[]}";
            }
            polishCalls++;
            if (failPolishCall) {
                throw new IllegalStateException("model unavailable");
            }
            if (userPrompt.contains("Current polished section text:")) {
                return "<output>\\section{Introduction} This paper presents a clearer RAG motivation \\cite{rag2020}.</output><explanation>x</explanation>";
            }
            if (customPolishOutput != null) {
                return "<output>" + customPolishOutput + "</output><explanation>x</explanation>";
            }
            if (returnExtraLabel) {
                return "<output>\\section{Introduction} This paper uses RAG. \\label{fig:new}</output>";
            }
            if (!preserveCitationOnFirstPolish && (userPrompt.contains("Attempt: 1 / 1") || polishCalls == 1)) {
                return "<output>\\section{Introduction} This polished text drops the citation.</output><explanation>x</explanation>";
            }
            return "<output>\\section{Introduction} This paper presents a clearer RAG motivation [[YANBAN_CITE_0001]].</output><explanation>x</explanation>";
        }
    }
}
