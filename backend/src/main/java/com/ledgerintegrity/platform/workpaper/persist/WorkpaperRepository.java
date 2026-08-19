package com.ledgerintegrity.platform.workpaper.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkpaperRepository extends JpaRepository<Workpaper, UUID> {

    List<Workpaper> findByEngagementIdOrderByVersionDesc(UUID engagementId);
}
