package com.yanban.api.admin;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminAccountInitializer {

    @Bean
    ApplicationRunner adminAccountBootstrap(AdminProperties properties,
                                            SysUserRepository users,
                                            PasswordEncoder passwordEncoder) {
        return args -> bootstrap(properties, users, passwordEncoder);
    }

    @Transactional
    void bootstrap(AdminProperties properties, SysUserRepository users, PasswordEncoder passwordEncoder) {
        if (!properties.isConfigured()) {
            return;
        }
        String username = properties.getUsername().trim();
        SysUser user = users.findByUsername(username)
                .orElseGet(() -> users.save(new SysUser(username, passwordEncoder.encode(properties.getPassword()))));
        user.setRole("ADMIN");
        users.saveAndFlush(user);
    }
}
