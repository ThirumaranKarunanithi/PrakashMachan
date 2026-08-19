package com.ledgerintegrity.platform.vendor.persist;

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

/** One vendor-master row per engagement (BRD §5 vendor/customer master). */
@Entity
@Table(name = "vendor_records",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class VendorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private String vendorId;

    @Column(nullable = false)
    private String name;

    private String gstin;
    private String bankAccount;
    private String ifsc;
    private LocalDate createdDate;
    private String createdBy;
    private String status;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected VendorRecord() {} // JPA

    public VendorRecord(UUID engagementId, String identityHash, String vendorId, String name,
                        String gstin, String bankAccount, String ifsc,
                        LocalDate createdDate, String createdBy, String status,
                        String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.vendorId = vendorId;
        this.name = name;
        this.gstin = gstin;
        this.bankAccount = bankAccount;
        this.ifsc = ifsc;
        this.createdDate = createdDate;
        this.createdBy = createdBy;
        this.status = status;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public String getVendorId() { return vendorId; }
    public String getName() { return name; }
    public String getGstin() { return gstin; }
    public String getBankAccount() { return bankAccount; }
    public String getIfsc() { return ifsc; }
    public LocalDate getCreatedDate() { return createdDate; }
    public String getCreatedBy() { return createdBy; }
    public String getStatus() { return status; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }
}
