package com.yanban.api.knowledge;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.knowledge.service.KnowledgeDocumentPublisherPolicy;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class KnowledgeDocumentPublisherPolicyAdapter implements KnowledgeDocumentPublisherPolicy {
    private final SysUserRepository users;

    public KnowledgeDocumentPublisherPolicyAdapter(SysUserRepository users) {
        this.users = users;
    }

    @Override
    public Set<Long> administratorIds() {
        return users.findActiveAdministratorIds();
    }

    @Override
    public boolean isAdministrator(Long userId) {
        return userId != null && users.findByIdAndDeletedAtIsNull(userId)
                .filter(user -> !user.isDeleted()).map(SysUser::isAdmin).orElse(false);
    }
}
