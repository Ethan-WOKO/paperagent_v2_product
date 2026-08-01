package com.yanban.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.security.JwtUser;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;

class ProjectWebSocketHandshakeInterceptorTest {

    @Test
    void bindsRouteProjectOnlyAfterAuthenticatedOwnerCheck() {
        ProjectService projects = mock(ProjectService.class);
        ProjectWebSocketHandshakeInterceptor interceptor = new ProjectWebSocketHandshakeInterceptor(projects);
        when(projects.manifest(7L, 18L)).thenReturn(new ProjectManifestResponse(18L, "m", List.of()));
        ServerHttpRequest request = request("ws://localhost/api/v1/ws/projects/18/chat?token=jwt");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(7L, "alice"));

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(ProjectWebSocketHandshakeInterceptor.ATTR_PROJECT_ID)).isEqualTo(18L);
        verify(projects).manifest(7L, 18L);
        verify(response, never()).setStatusCode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingAuthenticatedPrincipalBeforeProjectLookup() {
        ProjectService projects = mock(ProjectService.class);
        ProjectWebSocketHandshakeInterceptor interceptor = new ProjectWebSocketHandshakeInterceptor(projects);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(
                request("ws://localhost/api/v1/ws/projects/18/chat"),
                response,
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(projects, never()).manifest(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void hidesForeignOrUnavailableProjectAtHandshake() {
        ProjectService projects = mock(ProjectService.class);
        ProjectWebSocketHandshakeInterceptor interceptor = new ProjectWebSocketHandshakeInterceptor(projects);
        when(projects.manifest(7L, 99L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(7L, "alice"));

        boolean accepted = interceptor.beforeHandshake(
                request("ws://localhost/api/v1/ws/projects/99/chat"),
                response,
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).doesNotContainKey(ProjectWebSocketHandshakeInterceptor.ATTR_PROJECT_ID);
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsMalformedOrOverflowingRouteIds() {
        assertThat(ProjectWebSocketHandshakeInterceptor.projectId(
                URI.create("ws://localhost/api/v1/ws/projects/not-a-number/chat"))).isNull();
        assertThat(ProjectWebSocketHandshakeInterceptor.projectId(
                URI.create("ws://localhost/api/v1/ws/projects/999999999999999999999999/chat"))).isNull();
    }

    private ServerHttpRequest request(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }
}
