package com.yanban.api.demo;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoUserInitializer {

    private final SysUserRepository users;

    DemoUserInitializer(SysUserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SysUser create(String username, String passwordHash) {
        return users.saveAndFlush(new SysUser(
                username, passwordHash, null, DemoAccessService.ACCOUNT_TYPE_DEMO));
    }
}
