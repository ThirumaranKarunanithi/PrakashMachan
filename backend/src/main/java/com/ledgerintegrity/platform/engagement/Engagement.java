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
