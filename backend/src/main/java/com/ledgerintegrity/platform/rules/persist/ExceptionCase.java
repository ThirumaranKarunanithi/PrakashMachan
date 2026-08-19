package com.ledgerintegrity.platform.rules.persist;

import com.ledgerintegrity.platform.rules.Finding;
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
 * One exception in the BRD §3.3 life cycle. The system creates it as NEW and explains
 * why; only an authorised human moves it to a decision state (human judgement is final).
 * (engagement, identityHash) uniqueness makes rule re-runs idempotent — a resolved
 * exception does not reappear as new (GST-006 principle applied engine-wide).
 */
@Entity
@Table(name = "exception_cases",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class ExceptionCase {

    /** BRD §3.3 exception life cycle. */
    public enum Status {
        NEW, UNDER_REVIEW, INFO_REQUIRED,
        EXPLAINED,        // "Satisfactorily explained"
        CONFIRMED,        // "Confirmed exception"
        NOT_APPLICABLE, ESCALATED, CLOSED
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID ruleRunId;

    /** Consolidated investigation case this exception belongs to (BRD §17.2), once assigned. */
    private UUID caseId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private String ruleId;

    @Column(nullable = false)
    private String ruleName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Finding.Severity severity;

    private long exposurePaise;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false, length = 500)
    private String voucherIds;

    @Column(nullable = false, length = 2000)
    private String sourceRefs;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.NEW;

    @Column(length = 2000)
    private String decisionNote;

    private String decidedBy;
    private Instant decidedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected ExceptionCase() {} // JPA

    public static ExceptionCase from(Finding f, UUID engagementId, UUID ruleRunId, String identityHash, Instant now) {
        ExceptionCase e = new ExceptionCase();
        e.id = UUID.randomUUID();
        e.engagementId = engagementId;
        e.ruleRunId = ruleRunId;
        e.identityHash = identityHash;
        e.ruleId = f.ruleId();
        e.ruleName = f.ruleName();
        e.severity = f.severity();
        e.exposurePaise = f.exposurePaise();
        e.reason = truncate(f.reason(), 2000);
        e.voucherIds = truncate(String.join(" ", f.voucherIds()), 500);
        e.sourceRefs = truncate(f.sourceRefs(), 2000);
        e.createdAt = now;
        return e;
    }

    /** Record the auditor's decision — the documented professional judgement (CDC-006 spirit). */
    public void decide(Status newStatus, String note, String decidedBy, Instant when) {
        this.status = newStatus;
        this.decisionNote = note;
        this.decidedBy = decidedBy;
        this.decidedAt = when;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public UUID getRuleRunId() { return ruleRunId; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    public String getIdentityHash() { return identityHash; }
    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public Finding.Severity getSeverity() { return severity; }
    public long getExposurePaise() { return exposurePaise; }
    public String getReason() { return reason; }
    public String getVoucherIds() { return voucherIds; }
    public String getSourceRefs() { return sourceRefs; }
    public Status getStatus() { return status; }
    public String getDecisionNote() { return decisionNote; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
