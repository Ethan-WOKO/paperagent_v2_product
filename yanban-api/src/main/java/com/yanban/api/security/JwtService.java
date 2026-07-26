package com.yanban.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.yanban.api.user.SysUser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey secretKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.clock = Clock.systemUTC();
        if (!StringUtils.hasText(properties.getSecret()) || properties.getSecret().length() < 32) {
            throw new IllegalStateException("yanban.jwt.secret must be at least 32 characters");
        }
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String username) {
        return createToken(userId, username, 0L, "USER", "access", properties.getAccessTokenTtl().toSeconds());
    }

    public String createRefreshToken(Long userId, String username) {
        return createToken(userId, username, 0L, "USER", "refresh", properties.getRefreshTokenTtl().toSeconds());
    }

    public String createAccessToken(SysUser user) {
        return createToken(user.getId(), user.getUsername(), user.getLoginVersion(), user.getRole(), "access",
                properties.getAccessTokenTtl().toSeconds());
    }

    public String createRefreshToken(SysUser user) {
        return createToken(user.getId(), user.getUsername(), user.getLoginVersion(), user.getRole(), "refresh",
                properties.getRefreshTokenTtl().toSeconds());
    }

    public JwtUser parseAccessToken(String token) {
        Claims claims = parseClaims(token);
        if (!"access".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("JWT token type is not access");
        }
        return toUser(claims);
    }

    public JwtUser parseRefreshToken(String token) {
        Claims claims = parseClaims(token);
        if (!"refresh".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("JWT token type is not refresh");
        }
        return toUser(claims);
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    private String createToken(Long userId, String username, long loginVersion, String role, String type, long ttlSeconds) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("loginVersion", loginVersion)
                .claim("role", role)
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey)
                .compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private JwtUser toUser(Claims claims) {
        Number loginVersion = claims.get("loginVersion", Number.class);
        String role = claims.get("role", String.class);
        return new JwtUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class),
                loginVersion == null ? 0L : loginVersion.longValue(), role == null ? "USER" : role);
    }
}
