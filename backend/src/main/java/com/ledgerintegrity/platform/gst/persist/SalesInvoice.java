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

/** One sales-register invoice line (books side of GS-02). Amounts are integer paise. */
@Entity
@Table(name = "sales_invoices",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class SalesInvoice {

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

    private String customerId;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String gstin;

    private long taxablePaise;
    private long taxPaise;
    private long totalPaise;

    private String voucherId;

    /** GST-009: the entity's own registration this invoice belongs to (optional). */
    private String ownGstin;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected SalesInvoice() {} // JPA

    public SalesInvoice(UUID engagementId, String identityHash, String invoiceNo, LocalDate invoiceDate,
                        String customerId, String customerName, String gstin,
                        long taxablePaise, long taxPaise, long totalPaise, String voucherId,
                        String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.invoiceNo = invoiceNo;
        this.invoiceDate = invoiceDate;
        this.customerId = customerId;
        this.customerName = customerName;
        this.gstin = gstin;
        this.taxablePaise = taxablePaise;
        this.taxPaise = taxPaise;
        this.totalPaise = totalPaise;
        this.voucherId = voucherId;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public String getInvoiceNo() { return invoiceNo; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public String getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
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
