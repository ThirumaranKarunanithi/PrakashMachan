package com.ledgerintegrity.platform.evidence.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One evidence request (BRD §14): what document or explanation is needed, why, from
 * whom, by when — always linked to a specific exception (CDC-001). Closure requires a
 * documented sufficiency decision by the auditor (CDC-006).
 */
@Entity
@Table(name = "evidence_requests", indexes = @Index(columnList = "engagementId"))
public class EvidenceRequest {

    /** OPEN -> RESPONDED (client uploaded) -> ACCEPTED / REJECTED (auditor decision; REJECTED can be re-responded). */
    public enum Status { OPEN, RESPONDED, ACCEPTED, REJECTED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID exceptionId;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private String requestedBy;

    private LocalDate dueDate;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    @Column(length = 1000)
    private String decisionNote;

    private String decidedBy;
    private Instant decidedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected EvidenceRequest() {} // JPA

    public EvidenceRequest(UUID id, UUID engagementId, UUID exceptionId, String title, String description,
                           String requestedBy, LocalDate dueDate, Instant createdAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.exceptionId = exceptionId;
        this.title = title;
        this.description = description;
        this.requestedBy = requestedBy;
        this.dueDate = dueDate;
        this.createdAt = createdAt;
    }

    /** A new document arrived — the request is (re)responded, pending the auditor's decision. */
    public void markResponded() {
        if (status == Status.ACCEPTED) {
            throw new IllegalStateException("Request is already accepted; create a new request for further evidence.");
        }
        status = Status.RESPONDED;
    }

    /** CDC-006: the auditor records whether the response resolves the matter, with a reason. */
    public void decide(Status decision, String note, String decidedBy, Instant when) {
        if (decision != Status.ACCEPTED && decision != Status.REJECTED) {
            throw new IllegalArgumentException("Decision must be ACCEPTED or REJECTED.");
        }
        if (status != Status.RESPONDED) {
            throw new IllegalStateException("A decision requires a client response first (status is " + status + ").");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A documented reason is required for the sufficiency decision (CDC-006).");
        }
        this.status = decision;
        this.decisionNote = note;
        this.decidedBy = decidedBy;
        this.decidedAt = when;
    }

    public boolean isOverdue(LocalDate today) {
        return dueDate != null && (status == Status.OPEN || status == Status.REJECTED) && today.isAfter(dueDate);
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public UUID getExceptionId() { return exceptionId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getRequestedBy() { return requestedBy; }
    public LocalDate getDueDate() { return dueDate; }
    public Status getStatus() { return status; }
    public String getDecisionNote() { return decisionNote; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
