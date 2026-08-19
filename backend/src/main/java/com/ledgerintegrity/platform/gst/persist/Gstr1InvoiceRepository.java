package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface Gstr1InvoiceRepository extends JpaRepository<Gstr1Invoice, Long> {

    List<Gstr1Invoice> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select g.identityHash from Gstr1Invoice g where g.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
