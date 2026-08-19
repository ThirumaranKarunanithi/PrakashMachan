package com.ledgerintegrity.platform.gst.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * GST-007: a manual invoice link recorded by the GST professional — "these two records
 * are the same invoice despite the key mismatch" — with the reason, decider and time.
 * Durable across reconciliation re-runs and reviewable at any time.
 */
@Entity
@Table(name = "gst_manual_matches",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "side", "booksGstin", "booksInvoiceNo"}))
public class GstManualMatch {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GstMatchResult.Side side;

    @Column(nullable = false)
    private String booksGstin;

    @Column(nullable = false)
    private String booksInvoiceNo;

    @Column(nullable = false)
    private String portalGstin;

    @Column(nullable = false)
    private String portalInvoiceNo;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private String decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;

    protected GstManualMatch() {} // JPA

    public GstManualMatch(UUID id, UUID engagementId, GstMatchResult.Side side,
                          String booksGstin, String booksInvoiceNo,
                          String portalGstin, String portalInvoiceNo,
                          String reason, String decidedBy, Instant decidedAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.side = side;
        this.booksGstin = booksGstin;
        this.booksInvoiceNo = booksInvoiceNo;
        this.portalGstin = portalGstin;
        this.portalInvoiceNo = portalInvoiceNo;
        this.reason = reason;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public GstMatchResult.Side getSide() { return side; }
    public String getBooksGstin() { return booksGstin; }
    public String getBooksInvoiceNo() { return booksInvoiceNo; }
    public String getPortalGstin() { return portalGstin; }
    public String getPortalInvoiceNo() { return portalInvoiceNo; }
    public String getReason() { return reason; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
