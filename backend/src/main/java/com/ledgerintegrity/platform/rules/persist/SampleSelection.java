package com.ledgerintegrity.platform.rules.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JET-008 / BEN-013: one documented sample selection — risk-ranked or random control.
 * The seed makes random samples reproducible; both kinds appear in the workpaper.
 */
@Entity
@Table(name = "sample_selections", indexes = @Index(columnList = "engagementId"))
public class SampleSelection {

    public enum Method { RISK_RANKED, RANDOM }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, columnDefinition = "varchar(15)")
    private String method;

    private int sampleSize;

    /** RANDOM only: the PRNG seed — same seed + same population = same sample. */
    private Long seed;

    @Column(nullable = false, length = 4000)
    private String voucherIds;

    @Column(nullable = false)
    private String selectedBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected SampleSelection() {} // JPA

    public SampleSelection(UUID id, UUID engagementId, Method method, int sampleSize, Long seed,
                           String voucherIds, String selectedBy, Instant createdAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.method = method.name();
        this.sampleSize = sampleSize;
        this.seed = seed;
        this.voucherIds = voucherIds;
        this.selectedBy = selectedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public String getMethod() { return method; }
    public int getSampleSize() { return sampleSize; }
    public Long getSeed() { return seed; }
    public String getVoucherIds() { return voucherIds; }
    public String getSelectedBy() { return selectedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
