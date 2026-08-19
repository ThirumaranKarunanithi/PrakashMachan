package com.ledgerintegrity.platform.vendor.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AuditTrailEventRepository extends JpaRepository<AuditTrailEvent, Long> {

    List<AuditTrailEvent> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select a.identityHash from AuditTrailEvent a where a.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
