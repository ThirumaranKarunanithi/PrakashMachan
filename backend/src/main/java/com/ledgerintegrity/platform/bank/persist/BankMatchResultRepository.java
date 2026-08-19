package com.ledgerintegrity.platform.bank.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankMatchResultRepository extends JpaRepository<BankMatchResult, Long> {

    List<BankMatchResult> findByEngagementIdAndMatchTypeOrderByAmountPaiseDesc(UUID engagementId, BankMatchResult.MatchType matchType);

    void deleteByEngagementId(UUID engagementId);
}
