package com.yanban.api.ws;

import com.yanban.api.project.ProjectService;
import com.yanban.api.security.JwtUser;
import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Binds a route Project id to the authenticated owner before the Project WebSocket is established. */
@Component
public class ProjectWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_PROJECT_ID = "projectId";

    private static final Logger log = LoggerFactory.getLogger(ProjectWebSocketHandshakeInterceptor.class);
    private static final Pattern PROJECT_CHAT_PATH = Pattern.compile("/api/v1/ws/projects/([1-9][0-9]*)/chat/?$");

    private final ProjectService projects;

    public ProjectWebSocketHandshakeInterceptor(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        JwtUser currentUser = (JwtUser) attributes.get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        if (currentUser == null) {
            log.warn("Project WebSocket handshake rejected: missing authenticated principal remote={} path={}",
                    request.getRemoteAddress(), safePath(request.getURI()));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long projectId = projectId(request.getURI());
        if (projectId == null) {
            log.warn("Project WebSocket handshake rejected: invalid route userId={} remote={} path={}",
                    currentUser.id(), request.getRemoteAddress(), safePath(request.getURI()));
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        try {
            // Reuses the canonical owner/read-only/root checks. No client-supplied Project id reaches runtime.
            projects.manifest(currentUser.id(), projectId);
            attributes.put(ATTR_PROJECT_ID, projectId);
            log.debug("Project WebSocket handshake authorized userId={} projectId={} remote={}",
                    currentUser.id(), projectId, request.getRemoteAddress());
            return true;
        } catch (RuntimeException ex) {
            // Do not reveal whether another user's Project id exists or expose any root-path failure detail.
            log.warn("Project WebSocket handshake rejected userId={} projectId={} remote={} errorType={}",
                    currentUser.id(), projectId, request.getRemoteAddress(), ex.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.warn("Project WebSocket handshake completed with exception remote={} path={} errorType={}",
                    request.getRemoteAddress(), safePath(request.getURI()), exception.getClass().getSimpleName());
        }
    }

    static Long projectId(URI uri) {
        String path = safePath(uri);
        Matcher matcher = PROJECT_CHAT_PATH.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String safePath(URI uri) {
        return uri == null || uri.getPath() == null ? "" : uri.getPath();
    }
}
