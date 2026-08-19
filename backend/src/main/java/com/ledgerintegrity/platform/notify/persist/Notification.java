package com.ledgerintegrity.platform.notify.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-app notification (§18.3). The (firm, identityHash) uniqueness prevents
 * repetitive alerts for the same unresolved event (NFR-003).
 */
@Entity
@Table(name = "notifications",
        indexes = @Index(columnList = "firmId, createdAt"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"firmId", "identityHash"}))
public class Notification {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(length = 200)
    private String link;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readAt;

    protected Notification() {} // JPA

    public Notification(UUID id, UUID firmId, String identityHash, String type,
                        String message, String link, Instant createdAt) {
        this.id = id;
        this.firmId = firmId;
        this.identityHash = identityHash;
        this.type = type;
        this.message = message;
        this.link = link;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getLink() { return link; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void markRead(Instant when) { this.readAt = when; }
}
