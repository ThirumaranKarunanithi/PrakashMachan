package com.ledgerintegrity.platform.engagement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EngagementRepository extends JpaRepository<Engagement, UUID> {
    java.util.List<Engagement> findByFirmIdOrderByCreatedAtDesc(UUID firmId);
}
