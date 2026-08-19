package com.ledgerintegrity.platform.importer.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One normalised general-ledger line (standard field model, BRD §5).
 * Amounts are integer paise; exactly one of debit/credit is non-null on a valid line.
 * Optional source metadata is null when the source system does not supply it.
 */
public record LedgerRow(
        String voucherId,
        String voucherType,
        LocalDate txnDate,
        LocalDateTime createdAt,
        String accountCode,
        String accountName,
        Long debit,
        Long credit,
        String narration,
        String source,
        String userId,
        String reversalOf,
        Lineage lineage
) {
    public long debitPaise()  { return debit  == null ? 0L : debit; }
    public long creditPaise() { return credit == null ? 0L : credit; }
}
