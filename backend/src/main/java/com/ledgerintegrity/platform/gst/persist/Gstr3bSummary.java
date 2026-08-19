package com.ledgerintegrity.platform.gst.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** One GSTR-3B declared summary per tax period (GS-03). Amounts are integer paise. */
@Entity
@Table(name = "gstr3b_summaries",
        indexes = @Index(columnList = "engagementId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "period"}))
public class Gstr3bSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    /** Tax period, YYYY-MM. */
    @Column(nullable = false, length = 7)
    private String period;

    private long taxablePaise;
    private long taxPaise;

    @Column(nullable = false)
    private String sourceFile;
    @Column(nullable = false)
    private int sourceRow;

    protected Gstr3bSummary() {} // JPA

    public Gstr3bSummary(UUID engagementId, String period, long taxablePaise, long taxPaise,
                         String sourceFile, int sourceRow) {
        this.engagementId = engagementId;
        this.period = period;
        this.taxablePaise = taxablePaise;
        this.taxPaise = taxPaise;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
    }

    public UUID getEngagementId() { return engagementId; }
    public String getPeriod() { return period; }
    public long getTaxablePaise() { return taxablePaise; }
    public long getTaxPaise() { return taxPaise; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceRow() { return sourceRow; }
    public void setAmounts(long taxablePaise, long taxPaise) {
        this.taxablePaise = taxablePaise;
        this.taxPaise = taxPaise;
    }
}
