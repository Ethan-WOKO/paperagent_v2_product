package com.yanban.api.agent.v2.compatibility.literature;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ProductLiteratureSearchRequestAuthoritySource
        implements LiteratureSearchRequestAuthoritySource {
    private final LiteratureDeliveryJpaRepository deliveries;

    ProductLiteratureSearchRequestAuthoritySource(
            LiteratureDeliveryJpaRepository deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public Optional<LiteratureSearchRequestAuthority> find(
            Long userId, Long turnId) {
        return deliveries.findByIdUserIdAndTurnId(userId, turnId)
                .map(value -> new LiteratureSearchRequestAuthority(
                        value.query(), value.topK(), value.yearFrom(),
                        value.includeBibtex()));
    }
}
