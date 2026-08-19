package com.ledgerintegrity.platform.workpaper.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkpaperTemplateRepository extends JpaRepository<WorkpaperTemplate, UUID> {
    Optional<WorkpaperTemplate> findTopByFirmIdOrderByVersionDesc(UUID firmId);
}
