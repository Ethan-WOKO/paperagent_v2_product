package com.yanban.api.auth;

import com.yanban.api.invite.InviteCode;
import com.yanban.api.invite.InviteCodeProperties;
import com.yanban.api.invite.InviteCodeRepository;
import com.yanban.api.demo.DemoAccountService;
import com.yanban.api.error.ApiException;
import com.yanban.api.security.JwtService;
import com.yanban.api.security.JwtUser;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeProperties inviteCodeProperties;
    private final DemoAccountService demoAccountService;

    public AuthService(SysUserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       InviteCodeRepository inviteCodeRepository,
                       InviteCodeProperties inviteCodeProperties,
                       DemoAccountService demoAccountService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.inviteCodeRepository = inviteCodeRepository;
        this.inviteCodeProperties = inviteCodeProperties;
        this.demoAccountService = demoAccountService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已存在");
        }

        Long inviteCodeId = validateAndConsumeInviteCode(request.inviteCode());

        SysUser user = new SysUser(username, passwordEncoder.encode(request.password()), inviteCodeId);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已存在");
        }
        user.beginNewLogin();
        users.saveAndFlush(user);
        return tokensFor(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.username());
        SysUser user = users.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "账号不存在"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        user.beginNewLogin();
        users.saveAndFlush(user);
        return tokensFor(user);
    }

    @Transactional
    public AuthResponse demoLogin() {
        SysUser user = demoAccountService.prepareForLogin();
        user.beginNewLogin();
        users.saveAndFlush(user);
        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        JwtUser jwtUser = jwtService.parseRefreshToken(request.refreshToken());
        SysUser user = users.findByIdAndDeletedAtIsNull(jwtUser.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "账号不存在"));
        if (user.getLoginVersion() != jwtUser.loginVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED", "登录已在其他设备更新，请重新登录");
        }
        return tokensFor(user);
    }

    /**
     * Validates the invite code and atomically consumes one use.
     * Returns the invite code ID for user association, or null if the feature is disabled.
     */
    private Long validateAndConsumeInviteCode(String rawInviteCode) {
        if (!inviteCodeProperties.isEnabled()) {
            return null;
        }
        if (!StringUtils.hasText(rawInviteCode)) {
            throw inviteFailure("INVITE_CODE_REQUIRED", "请填写邀请码");
        }
        String code = rawInviteCode.trim();
        // Atomically increment used_count only if the code is valid and has remaining uses.
        int updated = inviteCodeRepository.incrementUsedCount(code);
        if (updated == 0) {
            InviteCode inviteCode = inviteCodeRepository.findByCode(code).orElse(null);
            if (inviteCode == null) {
                throw inviteFailure("INVITE_CODE_INVALID", "邀请码无效");
            }
            if (inviteCode.isDeleted()) {
                throw inviteFailure("INVITE_CODE_DELETED", "邀请码已删除");
            }
            if (!Boolean.TRUE.equals(inviteCode.getEnabled())) {
                throw inviteFailure("INVITE_CODE_DISABLED", "邀请码已停用");
            }
            throw inviteFailure("INVITE_CODE_EXHAUSTED", "邀请码使用次数已达上限");
        }
        return inviteCodeRepository.findByCode(code)
                .map(InviteCode::getId)
                .orElse(null);
    }

    private AuthResponse tokensFor(SysUser user) {
        return AuthResponse.bearer(
                jwtService.createAccessToken(user),
                jwtService.createRefreshToken(user),
                jwtService.accessTokenTtlSeconds()
        );
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }

    private ApiException inviteFailure(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, java.util.Map.of("inviteCode", message));
    }
}
