package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Gstr3bSummaryRepository extends JpaRepository<Gstr3bSummary, Long> {

    List<Gstr3bSummary> findByEngagementIdOrderByPeriodAsc(UUID engagementId);

    Optional<Gstr3bSummary> findByEngagementIdAndPeriod(UUID engagementId, String period);

    long countByEngagementId(UUID engagementId);
}
