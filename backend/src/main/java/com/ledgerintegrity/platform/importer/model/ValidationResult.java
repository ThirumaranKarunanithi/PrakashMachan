package com.ledgerintegrity.platform.importer.model;

import java.util.List;

/** DAT-002: debit/credit reconciliation and ledger-vs-trial-balance comparison. */
public record ValidationResult(
        long totalDebit,
        long totalCredit,
        boolean balanced,
        List<VoucherImbalance> voucherImbalances,
        List<TbDifference> tbDifferences,
        boolean tbAgrees
) {
    public record VoucherImbalance(String voucherId, long debit, long credit, long difference, int rows) {}

    public record TbDifference(
            String accountCode, String accountName,
            long ledgerDebit, long ledgerCredit,
            long tbDebit, long tbCredit,
            /** (ledger movement) - (tb movement), paise */
            long difference
    ) {}
}
