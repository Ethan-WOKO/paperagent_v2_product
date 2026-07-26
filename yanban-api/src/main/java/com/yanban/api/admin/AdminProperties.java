package com.yanban.api.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional bootstrap account. Keep both values blank to disable bootstrap. */
@ConfigurationProperties(prefix = "yanban.admin")
public class AdminProperties {

    private String username;
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
