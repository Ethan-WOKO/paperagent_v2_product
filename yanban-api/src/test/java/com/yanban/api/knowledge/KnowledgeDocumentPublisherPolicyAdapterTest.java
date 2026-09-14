package com.yanban.api.knowledge;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeDocumentPublisherPolicyAdapterTest {
    private final SysUserRepository users = mock(SysUserRepository.class);
    private final KnowledgeDocumentPublisherPolicyAdapter policy = new KnowledgeDocumentPublisherPolicyAdapter(users);

    @Test
    void queriesCurrentRoleRatherThanCachingOrTrustingViewerRole() {
        SysUser user = new SysUser("publisher", "unused");
        user.setRole("ADMIN");
        when(users.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(user));
        assertThat(policy.isAdministrator(9L)).isTrue();
        user.setRole("USER");
        assertThat(policy.isAdministrator(9L)).isFalse();
        user.setRole("ADMIN");
        user.deleteAccount();
        assertThat(policy.isAdministrator(9L)).isFalse();
    }

    @Test
    void missingIdentityIsNotAdministrator() {
        assertThat(policy.isAdministrator(null)).isFalse();
        when(users.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.empty());
        assertThat(policy.isAdministrator(9L)).isFalse();
    }

    @Test
    void batchQueryHandlesNoPublishers() {
        when(users.findActiveAdministratorIds()).thenReturn(Set.of(), Set.of(9L));
        assertThat(policy.administratorIds()).isEmpty();
        assertThat(policy.administratorIds()).containsExactly(9L);
    }
}
