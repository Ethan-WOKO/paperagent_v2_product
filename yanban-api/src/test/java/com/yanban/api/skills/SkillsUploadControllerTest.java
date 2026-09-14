package com.yanban.api.skills;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yanban.api.security.JwtUser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class SkillsUploadControllerTest {
    private final SkillsService service = mock(SkillsService.class);
    private final SkillsController controller = new SkillsController(service);
    private final JwtUser owner = new JwtUser(8L, "owner");

    @Test void delegatesOnlyAuthenticatedOwnerAndBothFileContents() throws Exception {
        controller.install(owner, request("{\"markdown\":{\"filename\":\"SKILL.md\",\"content\":\"# Review\"},\"metadata\":{\"filename\":\"skill.yaml\",\"content\":\"allowed_tools: []\"}}"));
        verify(service).install(eq(8L), argThat(upload -> upload.markdown().filename().equals("SKILL.md") && upload.metadata().content().equals("allowed_tools: []")));
    }

    @Test void rejectsLargeUnknownLengthBodyBeforeDelegation() {
        MockHttpServletRequest request = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return -1; } };
        request.setContent(new byte[SkillUploadParser.REQUEST_BYTES + 1]);
        assertThatThrownBy(() -> controller.install(owner, request)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(413));
        verifyNoInteractions(service);
    }

    @Test void rejectsOversizeDeclaredBodyBeforeReadingStream() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[SkillUploadParser.REQUEST_BYTES + 1]);
        assertThatThrownBy(() -> controller.install(owner, request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test void rejectsIdentityInjectionDuplicateKeysAndTrailingData() {
        for (String body : new String[]{"{\"userId\":9}", "{\"name\":\"one\",\"name\":\"two\"}", "{} {}", "invalid"}) {
            assertThatThrownBy(() -> controller.install(owner, request(body))).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(service);
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
