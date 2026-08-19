package com.ledgerintegrity.platform.workpaper.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * AWP-001: the firm's workpaper template — header/footer wording and which sections
 * the generated document includes. Versioned append-only like the risk weights.
 */
@Entity
@Table(name = "workpaper_templates", indexes = @Index(columnList = "firmId, version"))
public class WorkpaperTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 200)
    private String headerTitle;

    @Column(length = 500)
    private String footerNote;

    private boolean includeGst = true;
    private boolean includeBank = true;
    private boolean includeAuditTrail = true;

    @Column(nullable = false)
    private String updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WorkpaperTemplate() {} // JPA

    public WorkpaperTemplate(UUID id, UUID firmId, int version, String headerTitle, String footerNote,
                             boolean includeGst, boolean includeBank, boolean includeAuditTrail,
                             String updatedBy, Instant updatedAt) {
        this.id = id;
        this.firmId = firmId;
        this.version = version;
        this.headerTitle = headerTitle;
        this.footerNote = footerNote;
        this.includeGst = includeGst;
        this.includeBank = includeBank;
        this.includeAuditTrail = includeAuditTrail;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getFirmId() { return firmId; }
    public int getVersion() { return version; }
    public String getHeaderTitle() { return headerTitle; }
    public String getFooterNote() { return footerNote; }
    public boolean isIncludeGst() { return includeGst; }
    public boolean isIncludeBank() { return includeBank; }
    public boolean isIncludeAuditTrail() { return includeAuditTrail; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
