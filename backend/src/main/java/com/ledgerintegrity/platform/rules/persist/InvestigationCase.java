package com.ledgerintegrity.platform.rules.persist;

import com.ledgerintegrity.platform.rules.Finding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One consolidated investigation case (BRD §17.2 / RSK-002): related exceptions that
 * describe the same underlying event, grouped so the auditor sees one story instead
 * of several duplicate alerts. Review decisions stay on the member exceptions; the
 * case aggregates them.
 */
@Entity
@Table(name = "investigation_cases", indexes = @Index(columnList = "engagementId"))
public class InvestigationCase {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    /** Human-friendly sequential number within the engagement (CASE-001…). */
    @Column(nullable = false)
    private int caseNo;

    @Column(nullable = false, length = 300)
    private String title;

    /** Highest severity among member exceptions. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Finding.Severity severity;

    /**
     * Illustrative review-priority score (BRD §17.1: a starting design, NOT an approved
     * risk model): sum of member severity weights HIGH=10 / MEDIUM=5 / LOW=2.
     */
    @Column(nullable = false)
    private int priorityScore;

    /**
     * Largest member exposure, not the sum — members usually reference the same
     * underlying amounts, and summing would double-count (RSK-005 spirit).
     */
    private long exposurePaise;

    @Column(nullable = false, length = 1000)
    private String voucherIds;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // RSK-004: a reviewer may change the review priority without changing rule results.
    private Integer overriddenPriority;
    @Column(length = 500)
    private String overrideReason;
    private String overriddenBy;
    private Instant overriddenAt;

    protected InvestigationCase() {} // JPA

    public InvestigationCase(UUID id, UUID engagementId, int caseNo, Instant createdAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.caseNo = caseNo;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.title = "";
        this.severity = Finding.Severity.LOW;
    }

    /** Per-family score breakdown JSON (guide §9: every score shows its family facts). */
    @jakarta.persistence.Column(columnDefinition = "text")
    private String familyScoresJson;

    public void updateAggregates(String title, Finding.Severity severity, int priorityScore,
                                 long exposurePaise, String voucherIds, Instant now) {
        this.title = title;
        this.severity = severity;
        this.priorityScore = priorityScore;
        this.exposurePaise = exposurePaise;
        this.voucherIds = voucherIds;
        this.updatedAt = now;
    }

    public String getFamilyScoresJson() { return familyScoresJson; }
    public void setFamilyScoresJson(String json) { this.familyScoresJson = json; }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public int getCaseNo() { return caseNo; }
    public String getTitle() { return title; }
    public Finding.Severity getSeverity() { return severity; }
    public int getPriorityScore() { return priorityScore; }
    public long getExposurePaise() { return exposurePaise; }
    public String getVoucherIds() { return voucherIds; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** RSK-004: reviewer priority override — reason and reviewer are always recorded. */
    public void overridePriority(Integer priority, String reason, String reviewer, Instant when) {
        if (priority != null && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A recorded reason is required to override the priority (RSK-004).");
        }
        if (priority != null && (reviewer == null || reviewer.isBlank())) {
            throw new IllegalArgumentException("The overriding reviewer must be named (RSK-004).");
        }
        this.overriddenPriority = priority; // null clears the override
        this.overrideReason = priority == null ? null : reason.trim();
        this.overriddenBy = priority == null ? null : reviewer.trim();
        this.overriddenAt = priority == null ? null : when;
    }

    public Integer getOverriddenPriority() { return overriddenPriority; }
    public String getOverrideReason() { return overrideReason; }
    public String getOverriddenBy() { return overriddenBy; }
    public Instant getOverriddenAt() { return overriddenAt; }

    /** The priority the review queue actually uses: the reviewer's override, else the computed score. */
    public int effectivePriority() {
        return overriddenPriority != null ? overriddenPriority : priorityScore;
    }
}
