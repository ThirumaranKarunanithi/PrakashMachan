package com.ledgerintegrity.platform.bank.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankManualMatchRepository extends JpaRepository<BankManualMatch, UUID> {
    List<BankManualMatch> findByEngagementIdOrderByDecidedAtDesc(UUID engagementId);
}
