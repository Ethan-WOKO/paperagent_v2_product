package com.yanban.api.quota;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deliberately small first-version quota service. A non-negative quota is a token budget;
 * -1 keeps existing accounts unlimited until an administrator assigns a budget.
 */
@Service
public class UserQuotaService {

    private final SysUserRepository users;
    private final AiUsageRecordRepository usageRecords;

    public UserQuotaService(SysUserRepository users, AiUsageRecordRepository usageRecords) {
        this.users = users;
        this.usageRecords = usageRecords;
    }

    @Transactional(readOnly = true)
    public void assertCanUseAi(Long userId) {
        SysUser user = requireUser(userId);
        if (user.getAiQuotaTotal() >= 0L && user.getAiQuotaUsed() >= user.getAiQuotaTotal()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 额度已用完，请联系管理员");
        }
    }

    @Transactional
    public void recordUsage(Long userId, String feature, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        long prompt = safe(promptTokens);
        long completion = safe(completionTokens);
        long total = totalTokens == null ? safeAdd(prompt, completion) : safe(totalTokens);
        if (total <= 0L) {
            return;
        }
        SysUser user = requireUser(userId);
        user.addAiQuotaUsage(total);
        usageRecords.save(new AiUsageRecord(userId, feature, prompt, completion, total));
    }

    @Transactional
    public SysUser updateQuota(Long userId, long total, boolean resetUsed) {
        SysUser user = requireUser(userId);
        user.setAiQuotaTotal(total);
        if (resetUsed) {
            user.resetAiQuotaUsed();
        }
        return user;
    }

    @Transactional
    public SysUser resetQuotaUsage(Long userId) {
        SysUser user = requireUser(userId);
        user.resetAiQuotaUsed();
        return user;
    }

    private SysUser requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private long safe(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
