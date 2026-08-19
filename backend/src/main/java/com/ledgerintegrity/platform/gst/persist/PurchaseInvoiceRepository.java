package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, Long> {

    List<PurchaseInvoice> findByEngagementId(UUID engagementId);

    long countByEngagementId(UUID engagementId);

    @Query("select p.identityHash from PurchaseInvoice p where p.engagementId = :engagementId")
    List<String> findIdentityHashes(@Param("engagementId") UUID engagementId);
}
