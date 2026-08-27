package com.yanban.api.security;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SysUserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, SysUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try {
                JwtUser tokenUser = jwtService.parseAccessToken(token);
                SysUser persistedUser = users.findById(tokenUser.id())
                        .orElseThrow(() -> new IllegalArgumentException("User not found"));
                if (persistedUser.isDeleted()) {
                    throw new IllegalArgumentException("User account has been deleted");
                }
                if (persistedUser.getLoginVersion() != tokenUser.loginVersion()) {
                    throw new IllegalArgumentException("Session has been replaced by a newer login");
                }
                JwtUser user = new JwtUser(persistedUser.getId(), persistedUser.getUsername(),
                        persistedUser.getLoginVersion(), persistedUser.getRole());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null,
                                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
