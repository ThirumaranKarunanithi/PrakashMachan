package com.ledgerintegrity.platform.gst.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One purchase-register invoice line (books side of GS-01). Amounts are integer paise.
 * (engagement, identityHash) uniqueness gives duplicate-free delta imports (DAT-006).
 */
@Entity
@Table(name = "purchase_invoices",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class PurchaseInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private String invoiceNo;

    @Column(nullable = false)
    private LocalDate invoiceDate;

    private String vendorId;

    @Column(nullable = false)
    private String vendorName;

    @Column(nullable = false)
    private String gstin;

    private long taxablePaise;
    private long taxPaise;
    private long totalPaise;

    /** Link back to the GL voucher, when the register supplies it. */
    private String voucherId;

    /** GST-009: the entity's own registration this invoice belongs to (optional). */
    private String ownGstin;

    // lineage (DAT-005)
    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected PurchaseInvoice() {} // JPA

    public PurchaseInvoice(UUID engagementId, String identityHash, String invoiceNo, LocalDate invoiceDate,
                           String vendorId, String vendorName, String gstin,
                           long taxablePaise, long taxPaise, long totalPaise, String voucherId,
                           String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.invoiceNo = invoiceNo;
        this.invoiceDate = invoiceDate;
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.gstin = gstin;
        this.taxablePaise = taxablePaise;
        this.taxPaise = taxPaise;
        this.totalPaise = totalPaise;
        this.voucherId = voucherId;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public String getIdentityHash() { return identityHash; }
    public String getInvoiceNo() { return invoiceNo; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public String getVendorId() { return vendorId; }
    public String getVendorName() { return vendorName; }
    public String getGstin() { return gstin; }
    public long getTaxablePaise() { return taxablePaise; }
    public long getTaxPaise() { return taxPaise; }
    public long getTotalPaise() { return totalPaise; }
    public String getVoucherId() { return voucherId; }
    public String getOwnGstin() { return ownGstin; }
    public void setOwnGstin(String ownGstin) { this.ownGstin = ownGstin; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }
}
