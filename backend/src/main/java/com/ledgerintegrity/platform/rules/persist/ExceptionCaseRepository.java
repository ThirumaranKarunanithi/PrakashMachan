package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, UUID> {

    List<ExceptionCase> findByEngagementIdOrderBySeverityAscExposurePaiseDesc(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    /** Identities already raised for this engagement — makes re-runs idempotent. */
    @Query("select e.identityHash from ExceptionCase e where e.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
