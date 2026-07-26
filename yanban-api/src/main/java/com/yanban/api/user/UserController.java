package com.yanban.api.user;

import com.yanban.api.security.JwtUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final SysUserRepository users;

    public UserController(SysUserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public UserMeResponse me(@AuthenticationPrincipal JwtUser currentUser) {
        SysUser user = users.findById(currentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        String accountType = user.getAccountType() == null ? "NORMAL" : user.getAccountType();
        return new UserMeResponse(user.getId(), user.getUsername(), accountType,
                "DEMO".equalsIgnoreCase(accountType), user.getRole(), user.getAiQuotaTotal(),
                user.getAiQuotaUsed(), user.getAiQuotaRemaining());
    }
}
