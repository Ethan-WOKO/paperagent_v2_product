package com.yanban.api.security;

public record JwtUser(Long id, String username, long loginVersion, String role) {

    /** Kept for existing focused controller tests. */
    public JwtUser(Long id, String username) {
        this(id, username, 0L, "USER");
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
