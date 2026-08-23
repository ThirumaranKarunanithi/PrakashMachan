package com.ledgerintegrity.platform.engagement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One client-year engagement (BRD §3.2 step 1: client, year, materiality, team).
 * Materiality and team assignment arrive with the workflow increment.
 */
@Entity
@Table(name = "engagements")
public class Engagement {

    @Id
    private UUID id;

    /** Owning firm — the tenant boundary (SEC-001). */
    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false)
    private String clientName;

    @Column(nullable = false)
    private LocalDate fyStart;

    @Column(nullable = false)
    private LocalDate fyEnd;

    /** Financial close date — drives period-end and post-close rules. */
    @Column(nullable = false)
    private LocalDate closeDate;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /**
     * Comma-separated optional modules this client-year subscribes to (BRD subscription
     * model: Core is always included; GST / BANK / VENDOR / AUDIT_TRAIL are add-ons).
     * Explicit default so ddl-auto can add the column to already-populated tables,
     * keeping every existing engagement on the full suite.
     */
    @Column(nullable = false, columnDefinition = "varchar(100) default 'GST,BANK,VENDOR,AUDIT_TRAIL' not null")
    private String subscribedModules = "GST,BANK,VENDOR,AUDIT_TRAIL";

    protected Engagement() {} // JPA

    public Engagement(UUID id, UUID firmId, String clientName, LocalDate fyStart, LocalDate fyEnd, LocalDate closeDate, Instant createdAt) {
        this.id = id;
        this.firmId = firmId;
        this.clientName = clientName;
        this.fyStart = fyStart;
        this.fyEnd = fyEnd;
        this.closeDate = closeDate;
        this.createdAt = createdAt;
    }

    public java.util.Set<String> getSubscribedModules() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String m : subscribedModules.split(",")) if (!m.isBlank()) out.add(m.trim());
        return out;
    }

    public void setSubscribedModules(java.util.Set<String> modules) {
        this.subscribedModules = String.join(",", new java.util.TreeSet<>(modules));
    }

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public String getClientName() { return clientName; }
    public LocalDate getFyStart() { return fyStart; }
    public LocalDate getFyEnd() { return fyEnd; }
    public LocalDate getCloseDate() { return closeDate; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
