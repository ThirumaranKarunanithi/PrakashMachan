package com.ledgerintegrity.platform.benford.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BenfordRunRepository extends JpaRepository<BenfordRun, UUID> {
    List<BenfordRun> findByEngagementIdOrderByExecutedAtDesc(UUID engagementId);
}
