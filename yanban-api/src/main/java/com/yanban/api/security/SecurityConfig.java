package com.yanban.api.security;

import com.yanban.api.error.ApiSecurityErrorWriter;
import com.yanban.api.user.SysUserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiSecurityErrorWriter securityErrors;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ApiSecurityErrorWriter securityErrors) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityErrors = securityErrors;
    }

    @Bean
    public UserDetailsService userDetailsService(SysUserRepository users) {
        return username -> users.findByUsernameAndDeletedAtIsNull(username)
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities(user.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/demo/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/paper/events").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/observability/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                securityErrors.write(response, 401, "UNAUTHORIZED", "请登录后重试"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                securityErrors.write(response, 403, "FORBIDDEN", "没有权限执行此操作")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
