package com.ledgerintegrity.platform.rules.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * RSK-003: the methodology owner's severity weights for the review-priority score.
 * Versioned append-only — every change is a new row, so past configurations remain
 * reviewable and signed work is never silently altered.
 */
@Entity
@Table(name = "risk_weight_configs", indexes = @Index(columnList = "firmId, version"))
public class RiskWeightConfig {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false)
    private int version;

    private int highWeight;
    private int mediumWeight;
    private int lowWeight;

    // Family caps (guide §9.1): related signals cap inside their family so the total
    // 0-100 rises only when INDEPENDENT families corroborate. Explicit defaults so
    // ddl-auto can add the columns to already-populated tables.
    @Column(columnDefinition = "integer default 25 not null")
    private int reconciliationCap = DEFAULT_RECONCILIATION_CAP;
    @Column(columnDefinition = "integer default 25 not null")
    private int deterministicCap = DEFAULT_DETERMINISTIC_CAP;
    @Column(columnDefinition = "integer default 15 not null")
    private int behaviourCap = DEFAULT_BEHAVIOUR_CAP;
    @Column(columnDefinition = "integer default 10 not null")
    private int statisticalCap = DEFAULT_STATISTICAL_CAP;
    @Column(columnDefinition = "integer default 15 not null")
    private int relationshipCap = DEFAULT_RELATIONSHIP_CAP;
    @Column(columnDefinition = "integer default 10 not null")
    private int evidenceCap = DEFAULT_EVIDENCE_CAP;

    @Column(nullable = false)
    private String updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RiskWeightConfig() {} // JPA

    public RiskWeightConfig(UUID id, UUID firmId, int version,
                            int highWeight, int mediumWeight, int lowWeight,
                            String updatedBy, Instant updatedAt) {
        this.id = id;
        this.firmId = firmId;
        this.version = version;
        this.highWeight = highWeight;
        this.mediumWeight = mediumWeight;
        this.lowWeight = lowWeight;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** BRD §17.1 illustrative defaults — a starting design, not an approved model. */
    public static final int DEFAULT_HIGH = 10;
    public static final int DEFAULT_MEDIUM = 5;
    public static final int DEFAULT_LOW = 2;
    /** Guide §9.1 illustrative family caps; the methodology committee approves final values. */
    public static final int DEFAULT_RECONCILIATION_CAP = 25;
    public static final int DEFAULT_DETERMINISTIC_CAP = 25;
    public static final int DEFAULT_BEHAVIOUR_CAP = 15;
    public static final int DEFAULT_STATISTICAL_CAP = 10;
    public static final int DEFAULT_RELATIONSHIP_CAP = 15;
    public static final int DEFAULT_EVIDENCE_CAP = 10;

    public void setFamilyCaps(int reconciliation, int deterministic, int behaviour,
                              int statistical, int relationship, int evidence) {
        this.reconciliationCap = reconciliation;
        this.deterministicCap = deterministic;
        this.behaviourCap = behaviour;
        this.statisticalCap = statistical;
        this.relationshipCap = relationship;
        this.evidenceCap = evidence;
    }

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public int getVersion() { return version; }
    public int getHighWeight() { return highWeight; }
    public int getMediumWeight() { return mediumWeight; }
    public int getLowWeight() { return lowWeight; }
    public int getReconciliationCap() { return reconciliationCap; }
    public int getDeterministicCap() { return deterministicCap; }
    public int getBehaviourCap() { return behaviourCap; }
    public int getStatisticalCap() { return statisticalCap; }
    public int getRelationshipCap() { return relationshipCap; }
    public int getEvidenceCap() { return evidenceCap; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
