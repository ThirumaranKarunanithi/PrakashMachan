package com.ledgerintegrity.platform.importer.persist;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One import run for an engagement: manifest (DAT-001), quality issues (DAT-003),
 * validation outcome (DAT-002) and delta counts (DAT-006).
 */
@Entity
@Table(name = "import_batches", indexes = @Index(columnList = "engagementId"))
public class ImportBatch {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private String profileName;

    @Column(nullable = false)
    private Instant importedAt;

    // population + delta counts
    private int totalRows;
    private int cleanRows;
    private int addedRows;
    private int skippedRows;

    // validation outcome (paise)
    private long totalDebit;
    private long totalCredit;
    private boolean balanced;
    private boolean tbAgrees;
    private int voucherImbalanceCount;
    private int tbDifferenceCount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_files", joinColumns = @JoinColumn(name = "batch_id"))
    @OrderColumn(name = "position")
    private List<SourceFileRef> files = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "import_batch_issues", joinColumns = @JoinColumn(name = "batch_id"))
    @OrderColumn(name = "position")
    private List<QualityIssueRef> issues = new ArrayList<>();

    /** Plain classes, not records — Hibernate hydrates embeddables field-wise. */
    @Embeddable
    public static class SourceFileRef {
        private String fileName;
        private long bytes;
        private String sha256;
        private int rows;

        protected SourceFileRef() {}

        public SourceFileRef(String fileName, long bytes, String sha256, int rows) {
            this.fileName = fileName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.rows = rows;
        }

        public String fileName() { return fileName; }
        public long bytes() { return bytes; }
        public String sha256() { return sha256; }
        public int rows() { return rows; }
    }

    @Embeddable
    public static class QualityIssueRef {
        private String issueType;
        private String field;
        // "value" is a reserved word in H2/PostgreSQL — use an explicit column name
        @Column(name = "issue_value", length = 500)
        private String value;
        @Column(length = 1000)
        private String message;
        private String sourceFile;
        private int sourceRow;

        protected QualityIssueRef() {}

        public QualityIssueRef(String issueType, String field, String value, String message,
                               String sourceFile, int sourceRow) {
            this.issueType = issueType;
            this.field = field;
            this.value = value;
            this.message = message;
            this.sourceFile = sourceFile;
            this.sourceRow = sourceRow;
        }

        public String issueType() { return issueType; }
        public String field() { return field; }
        public String value() { return value; }
        public String message() { return message; }
        public String sourceFile() { return sourceFile; }
        public int sourceRow() { return sourceRow; }
    }

    protected ImportBatch() {} // JPA

    public ImportBatch(UUID id, UUID engagementId, String profileName, Instant importedAt) {
        this.id = id;
        this.engagementId = engagementId;
        this.profileName = profileName;
        this.importedAt = importedAt;
    }

    public UUID getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public String getProfileName() { return profileName; }
    public Instant getImportedAt() { return importedAt; }
    public int getTotalRows() { return totalRows; }
    public int getCleanRows() { return cleanRows; }
    public int getAddedRows() { return addedRows; }
    public int getSkippedRows() { return skippedRows; }
    public long getTotalDebit() { return totalDebit; }
    public long getTotalCredit() { return totalCredit; }
    public boolean isBalanced() { return balanced; }
    public boolean isTbAgrees() { return tbAgrees; }
    public int getVoucherImbalanceCount() { return voucherImbalanceCount; }
    public int getTbDifferenceCount() { return tbDifferenceCount; }
    public List<SourceFileRef> getFiles() { return files; }
    public List<QualityIssueRef> getIssues() { return issues; }

    public void setCounts(int totalRows, int cleanRows, int addedRows, int skippedRows) {
        this.totalRows = totalRows;
        this.cleanRows = cleanRows;
        this.addedRows = addedRows;
        this.skippedRows = skippedRows;
    }

    public void setValidation(long totalDebit, long totalCredit, boolean balanced, boolean tbAgrees,
                              int voucherImbalanceCount, int tbDifferenceCount) {
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.balanced = balanced;
        this.tbAgrees = tbAgrees;
        this.voucherImbalanceCount = voucherImbalanceCount;
        this.tbDifferenceCount = tbDifferenceCount;
    }
}
