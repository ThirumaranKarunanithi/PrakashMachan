package com.ledgerintegrity.platform.gst.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One classified GS-01 match outcome (GST-003: clear business categories, not a single
 * unmatched list). Derived data — rebuilt by every reconciliation run.
 */
@Entity
@Table(name = "gst_match_results", indexes = @Index(columnList = "engagementId"))
public class GstMatchResult {

    public enum Category { MATCHED, VALUE_MISMATCH, BOOKS_ONLY, G2B_ONLY, SUGGESTED }

    /** Which reconciliation produced this row: purchases vs 2B, or sales vs GSTR-1. */
    public enum Side { PURCHASE, SALES }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID reconciliationId;

    @Column(nullable = false, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    private Category category;

    /** nullable for pre-existing rows; treated as PURCHASE */
    @Column(columnDefinition = "varchar(10)")
    @Enumerated(EnumType.STRING)
    private Side side;

    /** true when the pairing came from a recorded manual link (GST-007); null = false on legacy rows */
    private Boolean manuallyLinked;

    /** GST-009: the entity's own registration (books side); null when unspecified */
    private String ownGstin;

    /** GST-002: fuzzy-suggestion confidence [0..1] and the fields that matched; null unless SUGGESTED */
    private Double confidence;
    @Column(length = 200)
    private String matchedFields;

    @Column(nullable = false)
    private String gstin;

    @Column(nullable = false)
    private String invoiceNo;

    @Column(nullable = false)
    private String partyName;

    private Long booksTaxablePaise;
    private Long booksTaxPaise;
    private Long g2bTaxablePaise;
    private Long g2bTaxPaise;

    /** Absolute tax difference for VALUE_MISMATCH; tax at stake for one-sided rows. */
    private long taxDiffPaise;

    private String voucherId;

    protected GstMatchResult() {} // JPA

    public GstMatchResult(UUID engagementId, UUID reconciliationId, Side side, Category category,
                          String gstin, String invoiceNo, String partyName,
                          Long booksTaxablePaise, Long booksTaxPaise,
                          Long g2bTaxablePaise, Long g2bTaxPaise,
                          long taxDiffPaise, String voucherId) {
        this.engagementId = engagementId;
        this.reconciliationId = reconciliationId;
        this.side = side;
        this.category = category;
        this.gstin = gstin;
        this.invoiceNo = invoiceNo;
        this.partyName = partyName;
        this.booksTaxablePaise = booksTaxablePaise;
        this.booksTaxPaise = booksTaxPaise;
        this.g2bTaxablePaise = g2bTaxablePaise;
        this.g2bTaxPaise = g2bTaxPaise;
        this.taxDiffPaise = taxDiffPaise;
        this.voucherId = voucherId;
    }

    public UUID getEngagementId() { return engagementId; }
    public UUID getReconciliationId() { return reconciliationId; }
    public Category getCategory() { return category; }
    public Side getSide() { return side == null ? Side.PURCHASE : side; }
    public boolean isManuallyLinked() { return Boolean.TRUE.equals(manuallyLinked); }
    public void markManuallyLinked() { this.manuallyLinked = true; }
    public String getOwnGstin() { return ownGstin; }
    public void setOwnGstin(String ownGstin) { this.ownGstin = ownGstin; }
    public Double getConfidence() { return confidence; }
    public String getMatchedFields() { return matchedFields; }
    public void setSuggestion(double confidence, String matchedFields) {
        this.confidence = confidence;
        this.matchedFields = matchedFields;
    }
    public String getGstin() { return gstin; }
    public String getInvoiceNo() { return invoiceNo; }
    public String getPartyName() { return partyName; }
    public Long getBooksTaxablePaise() { return booksTaxablePaise; }
    public Long getBooksTaxPaise() { return booksTaxPaise; }
    public Long getG2bTaxablePaise() { return g2bTaxablePaise; }
    public Long getG2bTaxPaise() { return g2bTaxPaise; }
    public long getTaxDiffPaise() { return taxDiffPaise; }
    public String getVoucherId() { return voucherId; }
}
