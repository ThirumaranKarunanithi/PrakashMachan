package com.ledgerintegrity.platform.workpaper.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of the engagement workpaper (BRD §15). The content is a snapshot
 * generated from platform state and is immutable once stored; sign-off is recorded
 * as metadata. A signed workpaper is locked — regeneration creates a new version
 * (AWP-006), and the SHA-256 makes any tampering evident.
 */
@Entity
@Table(name = "workpapers", indexes = @Index(columnList = "engagementId"))
public class Workpaper {

    /** DRAFT -> PREPARED (preparer) -> REVIEWED (manager) -> SIGNED (partner, locked). */
    public enum Status { DRAFT, PREPARED, REVIEWED, SIGNED }

    public enum Role { PREPARER, MANAGER, PARTNER }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Lob
    @Column(nullable = false)
    private String contentHtml;

    @Column(nullable = false, length = 64)
    private String contentSha256;

    @Column(nullable = false)
    private Instant createdAt;

    private String preparedBy;
    private Instant preparedAt;
    private String reviewedBy;
    private Instant reviewedAt;
    private String approvedBy;
    private Instant approvedAt;

    protected Workpaper() {} // JPA

    public Workpaper(UUID id, UUID engagementId, int version, String title,
                     String contentHtml, String contentSha256, Instant createdAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.version = version;
        this.title = title;
        this.contentHtml = contentHtml;
        this.contentSha256 = contentSha256;
        this.createdAt = createdAt;
    }

    /**
     * Record a role's sign-off (AWP-005). Enforces order, forbids signing a locked
     * workpaper, and forbids approving one's own earlier step.
     */
    public void sign(Role role, String name, Instant when) {
        if (status == Status.SIGNED) {
            throw new IllegalStateException("Workpaper v" + version + " is signed and locked; generate a new version instead.");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Signer name is required.");
        switch (role) {
            case PREPARER -> {
                if (status != Status.DRAFT) throw new IllegalStateException("Already prepared.");
                preparedBy = trimmed;
                preparedAt = when;
                status = Status.PREPARED;
            }
            case MANAGER -> {
                if (status != Status.PREPARED) throw new IllegalStateException("Preparer sign-off is required first.");
                if (trimmed.equalsIgnoreCase(preparedBy)) {
                    throw new IllegalArgumentException("Reviewer must be different from the preparer (AWP-005).");
                }
                reviewedBy = trimmed;
                reviewedAt = when;
                status = Status.REVIEWED;
            }
            case PARTNER -> {
                if (status != Status.REVIEWED) throw new IllegalStateException("Manager review is required first.");
                if (trimmed.equalsIgnoreCase(preparedBy) || trimmed.equalsIgnoreCase(reviewedBy)) {
                    throw new IllegalArgumentException("Approver must be different from preparer and reviewer (AWP-005).");
                }
                approvedBy = trimmed;
                approvedAt = when;
                status = Status.SIGNED;
            }
        }
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public String getContentHtml() { return contentHtml; }
    public String getContentSha256() { return contentSha256; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPreparedBy() { return preparedBy; }
    public Instant getPreparedAt() { return preparedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
