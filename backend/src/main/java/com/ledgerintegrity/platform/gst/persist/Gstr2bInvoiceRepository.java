package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface Gstr2bInvoiceRepository extends JpaRepository<Gstr2bInvoice, Long> {

    List<Gstr2bInvoice> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select g.identityHash from Gstr2bInvoice g where g.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
