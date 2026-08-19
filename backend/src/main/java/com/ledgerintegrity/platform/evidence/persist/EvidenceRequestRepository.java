package com.ledgerintegrity.platform.evidence.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceRequestRepository extends JpaRepository<EvidenceRequest, UUID> {

    List<EvidenceRequest> findByEngagementIdOrderByCreatedAtDesc(UUID engagementId);
}
