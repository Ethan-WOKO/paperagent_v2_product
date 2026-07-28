package com.yanban.api.agent.v2.compatibility.literature;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

interface LiteratureDeliveryJpaRepository
        extends JpaRepository<LiteratureDeliveryEntity, LiteratureDeliveryKey> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from LiteratureDeliveryEntity delivery "
            + "where delivery.id = :id")
    Optional<LiteratureDeliveryEntity> findLocked(
            @Param("id") LiteratureDeliveryKey id);

    Optional<LiteratureDeliveryEntity> findByIdUserIdAndTurnId(
            Long userId, Long turnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from LiteratureDeliveryEntity delivery "
            + "where delivery.id.userId = :userId "
            + "and delivery.turnId = :turnId")
    Optional<LiteratureDeliveryEntity> findLockedByUserIdAndTurnId(
            @Param("userId") Long userId, @Param("turnId") Long turnId);
}
