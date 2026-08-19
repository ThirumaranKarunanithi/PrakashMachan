package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GstManualMatchRepository extends JpaRepository<GstManualMatch, UUID> {

    List<GstManualMatch> findByEngagementIdOrderByDecidedAtDesc(UUID engagementId);

    List<GstManualMatch> findByEngagementIdAndSide(UUID engagementId, GstMatchResult.Side side);
}
