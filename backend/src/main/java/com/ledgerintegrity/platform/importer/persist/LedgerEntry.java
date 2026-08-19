package com.ledgerintegrity.platform.importer.persist;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.Lineage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One normalised general-ledger line, persisted per engagement.
 * The (engagement, identityHash) unique constraint enforces delta-import
 * dedup (DAT-006) at the database level, not just in application code.
 */
@Entity
@Table(name = "ledger_entries",
        indexes = {
                @Index(columnList = "engagementId"),
                @Index(columnList = "engagementId, voucherId"),
                @Index(columnList = "engagementId, accountCode")
        },
        uniqueConstraints = @UniqueConstraint(columnNames = {"engagementId", "identityHash"}))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private UUID engagementId;

    @Column(nullable = false)
    private UUID importBatchId;

    @Column(nullable = false, length = 64)
    private String identityHash;

    @Column(nullable = false)
    private String voucherId;

    @Column(nullable = false)
    private String voucherType;

    @Column(nullable = false)
    private LocalDate txnDate;

    private LocalDateTime createdTimestamp;

    @Column(nullable = false)
    private String accountCode;

    @Column(nullable = false)
    private String accountName;

    /** integer paise; null when the side is empty */
    private Long debit;
    private Long credit;

    @Column(nullable = false, length = 1000)
    private String narration;

    private String source;
    private String userId;
    private String reversalOf;

    // lineage (DAT-005)
    @Column(nullable = false)
    private String sourceFile;

    @Column(nullable = false)
    private int sourceRow;

    protected LedgerEntry() {} // JPA

    public static LedgerEntry from(LedgerRow r, UUID engagementId, UUID importBatchId, String identityHash) {
        LedgerEntry e = new LedgerEntry();
        e.engagementId = engagementId;
        e.importBatchId = importBatchId;
        e.identityHash = identityHash;
        e.voucherId = r.voucherId();
        e.voucherType = r.voucherType();
        e.txnDate = r.txnDate();
        e.createdTimestamp = r.createdAt();
        e.accountCode = r.accountCode();
        e.accountName = r.accountName();
        e.debit = r.debit();
        e.credit = r.credit();
        e.narration = r.narration();
        e.source = r.source();
        e.userId = r.userId();
        e.reversalOf = r.reversalOf();
        e.sourceFile = r.lineage().file();
        e.sourceRow = r.lineage().row();
        return e;
    }

    /** Back to the domain record used by rules and validation. */
    public LedgerRow toRow() {
        return new LedgerRow(voucherId, voucherType, txnDate, createdTimestamp,
                accountCode, accountName, debit, credit, narration,
                source, userId, reversalOf, new Lineage(sourceFile, sourceRow));
    }

    public Long getId() { return id; }
    public UUID getEngagementId() { return engagementId; }
    public UUID getImportBatchId() { return importBatchId; }
    public String getIdentityHash() { return identityHash; }
}
