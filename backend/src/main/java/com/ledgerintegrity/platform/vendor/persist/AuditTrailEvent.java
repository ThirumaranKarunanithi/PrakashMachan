package com.ledgerintegrity.platform.vendor.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/** One audit-trail / user-log event (BRD §5, §10): who changed what, when, old vs new. */
@Entity
@Table(name = "audit_trail_events",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class AuditTrailEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String objectType;

    @Column(nullable = false)
    private String recordId;

    @Column(nullable = false)
    private String field;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected AuditTrailEvent() {} // JPA

    public AuditTrailEvent(UUID engagementId, String identityHash, LocalDateTime timestamp, String userId,
                           String objectType, String recordId, String field,
                           String oldValue, String newValue, String action,
                           String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.timestamp = timestamp;
        this.userId = userId;
        this.objectType = objectType;
        this.recordId = recordId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.action = action;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getUserId() { return userId; }
    public String getObjectType() { return objectType; }
    public String getRecordId() { return recordId; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getAction() { return action; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }
}
