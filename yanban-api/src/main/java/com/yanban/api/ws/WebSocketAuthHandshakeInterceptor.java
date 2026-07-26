package com.yanban.api.ws;

import com.yanban.api.security.JwtService;
import com.yanban.api.security.JwtUser;
import com.yanban.api.user.SysUserRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class WebSocketAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthHandshakeInterceptor.class);

    static final String ATTR_JWT_USER = "jwtUser";

    private final JwtService jwtService;
    private final SysUserRepository users;

    @Autowired
    public WebSocketAuthHandshakeInterceptor(JwtService jwtService, SysUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    /** Compatibility constructor for focused websocket tests. */
    public WebSocketAuthHandshakeInterceptor(JwtService jwtService) {
        this(jwtService, null);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("WebSocket handshake rejected: non-servlet request remote={} path={}",
                    request.getRemoteAddress(),
                    request.getURI() == null ? null : request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String token = servletRequest.getServletRequest().getParameter("token");
        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket handshake rejected: missing token remote={} path={}",
                    request.getRemoteAddress(),
                    request.getURI() == null ? null : request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            JwtUser user = jwtService.parseAccessToken(token);
            boolean currentLogin = users == null || users.findById(user.id())
                    .map(value -> value.getLoginVersion() == user.loginVersion())
                    .orElse(false);
            if (!currentLogin) {
                throw new IllegalArgumentException("Session has been replaced by a newer login");
            }
            attributes.put(ATTR_JWT_USER, user);
            log.debug("WebSocket handshake accepted userId={} username={} remote={} path={}",
                    user.id(),
                    user.username(),
                    request.getRemoteAddress(),
                    request.getURI() == null ? null : request.getURI().getPath());
            return true;
        } catch (RuntimeException ex) {
            log.warn("WebSocket handshake rejected: invalid token remote={} path={} error={}",
                    request.getRemoteAddress(),
                    request.getURI() == null ? null : request.getURI().getPath(),
                    ex.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.warn("WebSocket handshake completed with exception remote={} path={} error={}",
                    request.getRemoteAddress(),
                    request.getURI() == null ? null : request.getURI().getPath(),
                    exception.getMessage(),
                    exception);
            return;
        }
        log.debug("WebSocket handshake completed remote={} path={}",
                request.getRemoteAddress(),
                request.getURI() == null ? null : request.getURI().getPath());
    }
}
