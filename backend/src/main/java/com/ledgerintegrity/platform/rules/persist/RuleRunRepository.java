package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RuleRunRepository extends JpaRepository<RuleRun, UUID> {
    List<RuleRun> findByEngagementIdOrderByExecutedAtDesc(UUID engagementId);
}
