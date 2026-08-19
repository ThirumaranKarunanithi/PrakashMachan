package com.ledgerintegrity.platform.importer.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
    List<ImportBatch> findByEngagementIdOrderByImportedAtDesc(UUID engagementId);
}
