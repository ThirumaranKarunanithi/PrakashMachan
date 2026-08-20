package com.ledgerintegrity.platform.benford.persist;

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
 * One Benford analysis execution (BRD §16). Stores population definition, exclusion
 * counts, the suitability verdict, statistics and the full bucket table so the run
 * is reproducible (BEN-012). The digit chart never outranks the suitability verdict.
 */
@Entity
@Table(name = "benford_runs", indexes = @Index(columnList = "engagementId"))
public class BenfordRun {

    public enum Population { MANUAL_JOURNALS, ALL_VOUCHERS, PAYMENTS, PURCHASES, SALES }

    public enum DigitTest { FIRST, SECOND, FIRST_TWO }

    /** BRD §16.4 recommended labels. */
    public enum Suitability { SUITABLE, SUITABLE_WITH_CAUTION, NOT_SUITABLE }

    /** Nigrini MAD conformity bands. */
    public enum Conformity { CLOSE, ACCEPTABLE, MARGINAL, NONCONFORMITY, NOT_ASSESSED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Population population;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DigitTest digitTest;

    @Column(nullable = false)
    private Instant executedAt;

    @Column(nullable = false, length = 2000)
    private String paramsJson;

    private int eligibleCount;
    private long eligibleValuePaise;
    private int excludedZeros;
    private int excludedNegatives;
    private int excludedReversals;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Suitability suitability;

    @Column(nullable = false, length = 1000)
    private String suitabilityReasons;

    private boolean suitabilityOverridden;

    @Column(length = 500)
    private String overrideReason;

    /** Mean absolute deviation of observed vs expected proportions; null when not assessed. */
    private Double mad;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Conformity conformity = Conformity.NOT_ASSESSED;

    // plain TEXT, not @Lob (PostgreSQL oid trap - see Workpaper.contentHtml)
    @Column(nullable = false, columnDefinition = "text")
    private String resultJson;

    private UUID createdExceptionId;

    protected BenfordRun() {} // JPA

    public BenfordRun(UUID id, UUID engagementId, Population population, DigitTest digitTest,
                      Instant executedAt, String paramsJson) {
        this.id = id;
        this.engagementId = engagementId;
        this.population = population;
        this.digitTest = digitTest;
        this.executedAt = executedAt;
        this.paramsJson = paramsJson;
        this.resultJson = "{}";
        this.suitability = Suitability.NOT_SUITABLE;
        this.suitabilityReasons = "";
    }

    public void setExclusions(int eligibleCount, long eligibleValuePaise,
                              int zeros, int negatives, int reversals) {
        this.eligibleCount = eligibleCount;
        this.eligibleValuePaise = eligibleValuePaise;
        this.excludedZeros = zeros;
        this.excludedNegatives = negatives;
        this.excludedReversals = reversals;
    }

    public void setSuitability(Suitability suitability, String reasons, boolean overridden, String overrideReason) {
        this.suitability = suitability;
        this.suitabilityReasons = reasons;
        this.suitabilityOverridden = overridden;
        this.overrideReason = overrideReason;
    }

    public void setOutcome(Double mad, Conformity conformity, String resultJson, UUID createdExceptionId) {
        this.mad = mad;
        this.conformity = conformity;
        this.resultJson = resultJson;
        this.createdExceptionId = createdExceptionId;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public Population getPopulation() { return population; }
    public DigitTest getDigitTest() { return digitTest; }
    public Instant getExecutedAt() { return executedAt; }
    public String getParamsJson() { return paramsJson; }
    public int getEligibleCount() { return eligibleCount; }
    public long getEligibleValuePaise() { return eligibleValuePaise; }
    public int getExcludedZeros() { return excludedZeros; }
    public int getExcludedNegatives() { return excludedNegatives; }
    public int getExcludedReversals() { return excludedReversals; }
    public Suitability getSuitability() { return suitability; }
    public String getSuitabilityReasons() { return suitabilityReasons; }
    public boolean isSuitabilityOverridden() { return suitabilityOverridden; }
    public String getOverrideReason() { return overrideReason; }
    public Double getMad() { return mad; }
    public Conformity getConformity() { return conformity; }
    public String getResultJson() { return resultJson; }
    public UUID getCreatedExceptionId() { return createdExceptionId; }
}
