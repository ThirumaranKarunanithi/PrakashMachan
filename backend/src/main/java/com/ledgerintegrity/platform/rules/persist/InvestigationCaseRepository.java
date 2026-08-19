package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase, UUID> {

    List<InvestigationCase> findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(UUID engagementId);

    long countByEngagementId(UUID engagementId);
}
