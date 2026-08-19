package com.ledgerintegrity.platform.evidence.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceDocumentRepository extends JpaRepository<EvidenceDocument, UUID> {

    List<EvidenceDocument> findByRequestIdOrderByVersionAsc(UUID requestId);
}
