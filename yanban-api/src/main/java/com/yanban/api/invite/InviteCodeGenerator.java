package com.yanban.api.invite;

import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int GROUPS = 4;
    private static final int GROUP_SIZE = 4;
    private static final int MAX_COLLISION_RETRIES = 8;

    private final SecureRandom random = new SecureRandom();
    private final InviteCodeRepository codes;

    public InviteCodeGenerator(InviteCodeRepository codes) {
        this.codes = codes;
    }

    @Transactional(readOnly = true)
    public String generate() {
        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            String candidate = candidate();
            if (!codes.existsByCode(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成唯一邀请码");
    }

    private String candidate() {
        StringBuilder value = new StringBuilder("YB");
        for (int group = 0; group < GROUPS; group++) {
            value.append('-');
            for (int index = 0; index < GROUP_SIZE; index++) {
                value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
        }
        return value.toString();
    }
}
