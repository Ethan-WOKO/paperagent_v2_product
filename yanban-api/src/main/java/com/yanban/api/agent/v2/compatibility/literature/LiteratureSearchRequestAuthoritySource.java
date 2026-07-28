package com.yanban.api.agent.v2.compatibility.literature;

import java.util.Optional;

public interface LiteratureSearchRequestAuthoritySource {
    Optional<LiteratureSearchRequestAuthority> find(Long userId, Long turnId);
}
