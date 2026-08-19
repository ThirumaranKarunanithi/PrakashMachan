package com.ledgerintegrity.platform.rules.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One execution of a rule pack against an engagement population. Stores the pack
 * version and exact parameter snapshot so the run is reproducible (JET-007/AWP-003).
 */
@Entity
@Table(name = "rule_runs", indexes = @Index(columnList = "engagementId"))
public class RuleRun {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private String packVersion;

    @Column(nullable = false, length = 4000)
    private String paramsJson;

    @Column(nullable = false)
    private Instant executedAt;

    private int populationVouchers;
    private long populationValuePaise;
    private int findings;
    private int exceptionsCreated;
    private int skippedExisting;

    protected RuleRun() {} // JPA

    public RuleRun(UUID id, UUID engagementId, String packVersion, String paramsJson, Instant executedAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.packVersion = packVersion;
        this.paramsJson = paramsJson;
        this.executedAt = executedAt;
    }

    public void setPopulationValue(long populationValuePaise) { this.populationValuePaise = populationValuePaise; }
    public long getPopulationValuePaise() { return populationValuePaise; }

    public void setOutcome(int populationVouchers, int findings, int exceptionsCreated, int skippedExisting) {
        this.populationVouchers = populationVouchers;
        this.findings = findings;
        this.exceptionsCreated = exceptionsCreated;
        this.skippedExisting = skippedExisting;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public String getPackVersion() { return packVersion; }
    public String getParamsJson() { return paramsJson; }
    public Instant getExecutedAt() { return executedAt; }
    public int getPopulationVouchers() { return populationVouchers; }
    public int getFindings() { return findings; }
    public int getExceptionsCreated() { return exceptionsCreated; }
    public int getSkippedExisting() { return skippedExisting; }
}
