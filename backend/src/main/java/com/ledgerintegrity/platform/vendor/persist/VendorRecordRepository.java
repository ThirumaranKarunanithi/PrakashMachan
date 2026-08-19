package com.ledgerintegrity.platform.vendor.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VendorRecordRepository extends JpaRepository<VendorRecord, Long> {

    List<VendorRecord> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select v.identityHash from VendorRecord v where v.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
