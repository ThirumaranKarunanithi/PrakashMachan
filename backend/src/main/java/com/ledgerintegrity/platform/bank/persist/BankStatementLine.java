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

/** One bank-statement transaction (bank side of BK-01..05). Amounts are integer paise. */
@Entity
@Table(name = "bank_statement_lines",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class BankStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 500)
    private String narration;

    @Column(nullable = false)
    private String reference;

    /** exactly one side is non-zero */
    private long debitPaise;
    private long creditPaise;
    private Long balancePaise;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected BankStatementLine() {} // JPA

    public BankStatementLine(UUID engagementId, String identityHash, LocalDate date, String narration,
                             String reference, long debitPaise, long creditPaise, Long balancePaise,
                             String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.identityHash = identityHash;
        this.date = date;
        this.narration = narration;
        this.reference = reference;
        this.debitPaise = debitPaise;
        this.creditPaise = creditPaise;
        this.balancePaise = balancePaise;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public Long getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public LocalDate getDate() { return date; }
    public String getNarration() { return narration; }
    public String getReference() { return reference; }
    public long getDebitPaise() { return debitPaise; }
    public long getCreditPaise() { return creditPaise; }
    public Long getBalancePaise() { return balancePaise; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }

    public long amountPaise() { return debitPaise + creditPaise; }
    /** true = money out of the bank (statement debit) */
    public boolean isOutflow() { return debitPaise > 0; }
}
