package com.ledgerintegrity.platform.auth.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {
    List<AuditLogEntry> findTop200ByFirmIdOrderByAtDesc(UUID firmId);
    long countByFirmId(UUID firmId);
}
