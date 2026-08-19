package com.ledgerintegrity.platform.bank.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BankLedgerLineRepository extends JpaRepository<BankLedgerLine, Long> {

    List<BankLedgerLine> findByEngagementIdOrderByDateAsc(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select b.identityHash from BankLedgerLine b where b.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
