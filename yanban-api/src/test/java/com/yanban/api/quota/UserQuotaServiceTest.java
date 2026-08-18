package com.yanban.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserQuotaServiceTest {
    private final SysUserRepository users = mock(SysUserRepository.class);
    private final AiUsageRecordRepository records = mock(AiUsageRecordRepository.class);
    private final UserQuotaService service = new UserQuotaService(users, records);

    @Test
    void recordsPromptAndCompletionUnderAUserLock() {
        SysUser user = new SysUser("owner", "hash");
        when(users.findLockedById(7L)).thenReturn(Optional.of(user));

        service.recordUsage(7L, "REACT_PLAN", 12, 8, null);

        assertThat(user.getAiQuotaUsed()).isEqualTo(20);
        verify(users).findLockedById(7L);
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getPromptTokens() == 12 && record.getCompletionTokens() == 8
                        && record.getTotalTokens() == 20));
    }

    @Test
    void zeroUsageDoesNotLockOrCreateHistory() {
        service.recordUsage(7L, "REACT_PLAN", 0, 0, null);
        verify(users, never()).findLockedById(7L);
        verify(records, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsOneTaskAggregateWithLongTokenCounts() {
        SysUser user = new SysUser("owner", "hash");
        when(users.findLockedById(7L)).thenReturn(Optional.of(user));

        service.recordTaskUsage(7L, "REACT_PLAN", 30L, 12L);

        assertThat(user.getAiQuotaUsed()).isEqualTo(42L);
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getPromptTokens() == 30L && record.getCompletionTokens() == 12L
                        && record.getTotalTokens() == 42L));
    }
}
