package com.yanban.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 64, message = "用户名长度必须为 3 到 64 个字符")
        @Pattern(regexp = "^[a-zA-Z0-9_@.\\-]+$", message = "用户名只允许字母、数字、下划线、@、点和横线")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(min = 8, max = 128, message = "密码长度必须为 8 到 128 个字符")
        String password,

        String inviteCode
) {
    public RegisterRequest {
        username = username == null ? null : username.trim();
        inviteCode = inviteCode == null ? null : inviteCode.trim();
    }
}
