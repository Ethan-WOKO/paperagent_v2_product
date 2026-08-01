package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.yanban.paper.literature.AdHocLiteratureSearchService;
import com.yanban.paper.literature.LiteratureCandidate;
import com.yanban.paper.literature.LiteratureSource;
import com.yanban.paper.literature.StandaloneLiteratureCardSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationIntentRouterServiceTest {

    @Test
    void explicitLiteratureCommandTriggersSearch() {
        ConversationIntentRouterService router = new ConversationIntentRouterService(
                new PaperRevisionIntentService(),
                new AdHocLiteratureSearchService(List.of(fakeSource()), mock(StandaloneLiteratureCardSearchService.class))
        );

        ConversationIntentRouterService.IntentAction action = router.route("/literature polarimetric FDA-MIMO self-protection jamming 1篇 bibtex");

        assertThat(action).isNotNull();
        assertThat(action.intent()).isEqualTo("LITERATURE_SEARCH");
        assertThat(action.navigationUrl()).isNull();
        assertThat(action.assistantMessage()).contains("Polarimetric FDA-MIMO", "```bibtex");
    }

    @Test
    void semanticLiteratureHintAsksConfirmationInsteadOfSearching() {
        ConversationIntentRouterService router = new ConversationIntentRouterService(
                new PaperRevisionIntentService(),
                new AdHocLiteratureSearchService(List.of(fakeSource()), mock(StandaloneLiteratureCardSearchService.class))
        );

        ConversationIntentRouterService.IntentAction action = router.route("这个方向最近有什么工作 FDA-MIMO jamming");

        assertThat(action).isNotNull();
        assertThat(action.intent()).isEqualTo("LITERATURE_SEARCH_CONFIRM");
        assertThat(action.assistantMessage()).contains("/literature FDA-MIMO jamming");
        assertThat(action.assistantMessage()).doesNotContain("Polarimetric FDA-MIMO");
    }

    @Test
    void genericReferencesQuestionDoesNotTriggerLiteratureSearch() {
        ConversationIntentRouterService router = new ConversationIntentRouterService(
                new PaperRevisionIntentService(),
                new AdHocLiteratureSearchService(List.of(fakeSource()), mock(StandaloneLiteratureCardSearchService.class))
        );

        assertThat(router.route("Java references 是什么意思")).isNull();
    }

    @Test
    void paperRevisionIntentStillReturnsPaperNavigation() {
        ConversationIntentRouterService router = new ConversationIntentRouterService(
                new PaperRevisionIntentService(),
                new AdHocLiteratureSearchService(List.of(fakeSource()), mock(StandaloneLiteratureCardSearchService.class))
        );

        ConversationIntentRouterService.IntentAction action = router.route("帮我润色论文");

        assertThat(action).isNotNull();
        assertThat(action.intent()).isEqualTo("PAPER_REVISION");
        assertThat(action.navigationUrl()).isEqualTo("/paper");
    }

    private LiteratureSource fakeSource() {
        return new LiteratureSource() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public List<LiteratureCandidate> search(String query, int limit) {
                return List.of(new LiteratureCandidate("fake", "10.1109/demo", null, "W1", null,
                        "Polarimetric FDA-MIMO Radar for Self-Protection Jamming Suppression",
                        List.of("Alice Zhang"), 2024, "IEEE TAES",
                        "polarimetric FDA-MIMO self-protection jamming suppression", "https://doi.org/10.1109/demo", null,
                        10, List.of(), List.of("Radar"), query));
            }
        };
    }
}
