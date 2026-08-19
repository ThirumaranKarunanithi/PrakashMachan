package com.ledgerintegrity.platform.bank.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * BKR-003: a manual bank match recorded by the reviewer — "this statement entry IS this
 * book voucher" — with reason, decider and time. Applied on every reconciliation run.
 */
@Entity
@Table(name = "bank_manual_matches",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "statementReference"}))
public class BankManualMatch {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private String statementReference;

    @Column(nullable = false)
    private String voucherId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private String decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;

    protected BankManualMatch() {} // JPA

    public BankManualMatch(UUID id, UUID engagementId, String statementReference, String voucherId,
                           String reason, String decidedBy, Instant decidedAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.statementReference = statementReference;
        this.voucherId = voucherId;
        this.reason = reason;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public String getStatementReference() { return statementReference; }
    public String getVoucherId() { return voucherId; }
    public String getReason() { return reason; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
