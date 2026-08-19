package com.ledgerintegrity.platform.importer.model;

/** One trial-balance line. Amounts are integer paise. */
public record TrialBalanceRow(
        String accountCode,
        String accountName,
        long opening,
        long debit,
        long credit,
        long closing,
        Lineage lineage
) {}
