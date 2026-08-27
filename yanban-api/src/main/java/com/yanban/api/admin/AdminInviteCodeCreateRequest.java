package com.yanban.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminInviteCodeCreateRequest(
        @NotBlank(message = "请先生成邀请码")
        @Pattern(
                regexp = "^YB-[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$",
                message = "邀请码格式不正确，请重新生成")
        String code,

        @Min(value = 1, message = "最大使用次数至少为 1")
        @Max(value = 100000, message = "最大使用次数不能超过 100000")
        int maxUses
) {
    public AdminInviteCodeCreateRequest {
        code = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
