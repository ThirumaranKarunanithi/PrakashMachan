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

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public int getVersion() { return version; }
    public int getHighWeight() { return highWeight; }
    public int getMediumWeight() { return mediumWeight; }
    public int getLowWeight() { return lowWeight; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
