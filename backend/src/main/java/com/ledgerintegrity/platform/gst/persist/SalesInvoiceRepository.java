package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, Long> {

    List<SalesInvoice> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select s.identityHash from SalesInvoice s where s.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
