package com.ledgerintegrity.platform.bank.persist;

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
 * One bank-ledger entry from the books (books side of BK-01..05).
 * Books convention: debit = money into the bank account, credit = money out.
 */
@Entity
@Table(name = "bank_ledger_lines",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class BankLedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String voucherId;

    @Column(nullable = false)
    private String reference;

    private long debitPaise;
    private long creditPaise;

    @Column(length = 500)
    private String narration;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected BankLedgerLine() {} // JPA

    public BankLedgerLine(UUID engagementId, String identityHash, LocalDate date, String voucherId,
                          String reference, long debitPaise, long creditPaise, String narration,
                          String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.date = date;
        this.voucherId = voucherId;
        this.reference = reference;
        this.debitPaise = debitPaise;
        this.creditPaise = creditPaise;
        this.narration = narration;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public Long getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public LocalDate getDate() { return date; }
    public String getVoucherId() { return voucherId; }
    public String getReference() { return reference; }
    public long getDebitPaise() { return debitPaise; }
    public long getCreditPaise() { return creditPaise; }
    public String getNarration() { return narration; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }

    public long amountPaise() { return debitPaise + creditPaise; }
    /** true = money out of the bank (books credit the bank account) */
    public boolean isOutflow() { return creditPaise > 0; }
}
