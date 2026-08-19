package com.ledgerintegrity.platform.bank.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** One classified bank-reconciliation outcome. Derived data — rebuilt each run. */
@Entity
@Table(name = "bank_match_results", indexes = @Index(columnList = "engagementId"))
public class BankMatchResult {

    public enum MatchType { EXACT, TOLERANCE, GROUPED, MANUAL, BANK_ONLY, BOOKS_ONLY }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID reconciliationId;

    @Column(nullable = false, columnDefinition = "varchar(12)")
    @Enumerated(EnumType.STRING)
    private MatchType matchType;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String reference;

    @Column(nullable = false, length = 500)
    private String description;

    private long amountPaise;

    /** true = money out of the bank */
    private boolean outflow;

    /** space-separated book voucher ids involved (empty for bank-only) */
    @Column(nullable = false, length = 500)
    private String voucherIds;

    /** days between statement and book dates (0 for exact; null for one-sided) */
    private Integer dateGapDays;

    protected BankMatchResult() {} // JPA

    public BankMatchResult(UUID engagementId, UUID reconciliationId, MatchType matchType, LocalDate date,
                           String reference, String description, long amountPaise, boolean outflow,
                           String voucherIds, Integer dateGapDays) {
        this.engagementId = engagementId;
        this.reconciliationId = reconciliationId;
        this.matchType = matchType;
        this.date = date;
        this.reference = reference;
        this.description = description;
        this.amountPaise = amountPaise;
        this.outflow = outflow;
        this.voucherIds = voucherIds;
        this.dateGapDays = dateGapDays;
    }

    public UUID getEngagementId() { return engagementId; }
    public MatchType getMatchType() { return matchType; }
    public LocalDate getDate() { return date; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public long getAmountPaise() { return amountPaise; }
    public boolean isOutflow() { return outflow; }
    public String getVoucherIds() { return voucherIds; }
    public Integer getDateGapDays() { return dateGapDays; }
}
