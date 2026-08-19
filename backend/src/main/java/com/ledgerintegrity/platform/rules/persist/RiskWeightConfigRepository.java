package com.ledgerintegrity.platform.rules.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskWeightConfigRepository extends JpaRepository<RiskWeightConfig, UUID> {
    Optional<RiskWeightConfig> findTopByFirmIdOrderByVersionDesc(UUID firmId);
}
