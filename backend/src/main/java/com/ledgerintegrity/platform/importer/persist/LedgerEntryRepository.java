package com.ledgerintegrity.platform.importer.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByEngagementId(UUID engagementId);

    List<LedgerEntry> findByEngagementId(UUID engagementId);

    List<LedgerEntry> findByEngagementIdAndVoucherId(UUID engagementId, String voucherId);

    List<LedgerEntry> findByEngagementIdAndSourceFileAndSourceRowBetweenOrderBySourceRowAsc(
            UUID engagementId, String sourceFile, int fromRow, int toRow);

    /** Existing content identities for an engagement — input to delta import (DAT-006). */
    @Query("select e.identityHash from LedgerEntry e where e.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
