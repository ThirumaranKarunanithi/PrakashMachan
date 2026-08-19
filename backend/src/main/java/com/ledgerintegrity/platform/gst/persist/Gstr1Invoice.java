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

/** One GSTR-1 outward-supply line (portal side of GS-02). Amounts are integer paise. */
@Entity
@Table(name = "gstr1_invoices",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class Gstr1Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private String customerGstin;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String invoiceNo;

    @Column(nullable = false)
    private LocalDate invoiceDate;

    private long taxablePaise;
    private long taxPaise;

    private String filingStatus;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected Gstr1Invoice() {} // JPA

    public Gstr1Invoice(UUID engagementId, String identityHash, String customerGstin, String customerName,
                        String invoiceNo, LocalDate invoiceDate, long taxablePaise, long taxPaise,
                        String filingStatus, String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.customerGstin = customerGstin;
        this.customerName = customerName;
        this.invoiceNo = invoiceNo;
        this.invoiceDate = invoiceDate;
        this.taxablePaise = taxablePaise;
        this.taxPaise = taxPaise;
        this.filingStatus = filingStatus;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public String getCustomerGstin() { return customerGstin; }
    public String getCustomerName() { return customerName; }
    public String getInvoiceNo() { return invoiceNo; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public long getTaxablePaise() { return taxablePaise; }
    public long getTaxPaise() { return taxPaise; }
    public String getFilingStatus() { return filingStatus; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }
}
