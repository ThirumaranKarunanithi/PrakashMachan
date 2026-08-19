package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SampleSelectionRepository extends JpaRepository<SampleSelection, UUID> {
    List<SampleSelection> findByEngagementIdOrderByCreatedAtDesc(UUID engagementId);
}
