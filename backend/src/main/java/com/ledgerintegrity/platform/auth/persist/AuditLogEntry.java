package com.ledgerintegrity.platform.auth.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** SEC-004: one logged API action — who did what, when, with what outcome. Append-only. */
@Entity
@Table(name = "audit_log", indexes = @Index(columnList = "firmId, at"))
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private UUID firmId;

    @Column(nullable = false)
    private String userEmail;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(nullable = false)
    private int status;

    @Column(nullable = false)
    private Instant at;

    protected AuditLogEntry() {} // JPA

    public AuditLogEntry(UUID firmId, String userEmail, String method, String path, int status, Instant at) {
        this.firmId = firmId;
        this.userEmail = userEmail;
        this.method = method;
        this.path = path;
        this.status = status;
        this.at = at;
    }

    public UUID getFirmId() { return firmId; }
    public String getUserEmail() { return userEmail; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public int getStatus() { return status; }
    public Instant getAt() { return at; }
}
