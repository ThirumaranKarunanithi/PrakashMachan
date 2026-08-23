package com.ledgerintegrity.platform.engagement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The firm's per-client-year price list (Screen 2: "pricing is based on selected
 * modules per client-year"). Core is always charged; add-on modules price on top.
 * Versioned append-only like every methodology setting; the defaults are
 * ILLUSTRATIVE placeholders for the firm to replace.
 */
@Entity
@Table(name = "price_configs", indexes = @Index(columnList = "firmId, version"))
public class PriceConfig {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID firmId;

    @Column(nullable = false)
    private int version;

    private long corePaise;
    private long gstPaise;
    private long bankPaise;
    private long vendorPaise;
    private long auditTrailPaise;

    @Column(nullable = false)
    private String updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PriceConfig() {} // JPA

    public PriceConfig(UUID id, UUID firmId, int version, long corePaise, long gstPaise,
                       long bankPaise, long vendorPaise, long auditTrailPaise,
                       String updatedBy, Instant updatedAt) {
        this.id = id;
        this.firmId = firmId;
        this.version = version;
        this.corePaise = corePaise;
        this.gstPaise = gstPaise;
        this.bankPaise = bankPaise;
        this.vendorPaise = vendorPaise;
        this.auditTrailPaise = auditTrailPaise;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** Illustrative defaults (INR per client-year) — replace under Pricing & Plans. */
    public static final long DEFAULT_CORE = 25_000_00L;
    public static final long DEFAULT_GST = 10_000_00L;
    public static final long DEFAULT_BANK = 8_000_00L;
    public static final long DEFAULT_VENDOR = 8_000_00L;
    public static final long DEFAULT_AUDIT_TRAIL = 6_000_00L;

    public long priceFor(Module m) {
        return switch (m) {
            case GST -> gstPaise;
            case BANK -> bankPaise;
            case VENDOR -> vendorPaise;
            case AUDIT_TRAIL -> auditTrailPaise;
        };
    }

    public static PriceConfig defaults(UUID firmId) {
        return new PriceConfig(UUID.randomUUID(), firmId, 0, DEFAULT_CORE, DEFAULT_GST,
                DEFAULT_BANK, DEFAULT_VENDOR, DEFAULT_AUDIT_TRAIL,
                "defaults (illustrative)", Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getFirmId() { return firmId; }
    public int getVersion() { return version; }
    public long getCorePaise() { return corePaise; }
    public long getGstPaise() { return gstPaise; }
    public long getBankPaise() { return bankPaise; }
    public long getVendorPaise() { return vendorPaise; }
    public long getAuditTrailPaise() { return auditTrailPaise; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
