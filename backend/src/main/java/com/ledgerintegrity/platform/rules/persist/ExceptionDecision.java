package com.ledgerintegrity.platform.rules.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only history of an exception's status changes. The current status on
 * ExceptionCase is a pointer to the latest entry; nothing here is ever updated
 * or deleted, so an auto-transition can no longer erase an auditor's reasoning.
 */
@Entity
@Table(name = "exception_decisions", indexes = @Index(columnList = "exceptionId"))
public class ExceptionDecision {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID exceptionId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private ExceptionCase.Status fromStatus;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private ExceptionCase.Status toStatus;

    @Column(length = 2000)
    private String note;

    /** Session-derived identity of who made the change (never client-supplied). */
    @Column(nullable = false)
    private String decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;

    protected ExceptionDecision() {} // JPA

    public ExceptionDecision(UUID id, UUID engagementId, UUID exceptionId,
                             ExceptionCase.Status fromStatus, ExceptionCase.Status toStatus,
                             String note, String decidedBy, Instant decidedAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.exceptionId = exceptionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public UUID getExceptionId() { return exceptionId; }
    public ExceptionCase.Status getFromStatus() { return fromStatus; }
    public ExceptionCase.Status getToStatus() { return toStatus; }
    public String getNote() { return note; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
