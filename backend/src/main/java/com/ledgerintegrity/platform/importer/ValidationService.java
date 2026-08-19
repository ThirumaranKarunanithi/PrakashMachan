package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.TrialBalanceRow;
import com.ledgerintegrity.platform.importer.model.ValidationResult;
import com.ledgerintegrity.platform.importer.model.ValidationResult.TbDifference;
import com.ledgerintegrity.platform.importer.model.ValidationResult.VoucherImbalance;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DAT-002: reconcile journal debits/credits and compare ledger totals with the trial balance. */
@Service
public class ValidationService {

    private static final class Sum { long debit; long credit; int rows; String name; }

    public ValidationResult validate(List<LedgerRow> rows, List<TrialBalanceRow> tb) {
        return validate(rows, tb, true);
    }

    /**
     * tbProvided=false skips the ledger-vs-TB comparison (vacuously agreeing) for
     * sources that ship no trial balance — the limitation is recorded as a quality
     * issue by the caller so DAT-002 coverage stays visible.
     */
    public ValidationResult validate(List<LedgerRow> rows, List<TrialBalanceRow> tb, boolean tbProvided) {
        long totalDebit = 0;
        long totalCredit = 0;
        Map<String, Sum> byVoucher = new LinkedHashMap<>();
        Map<String, Sum> byAccount = new LinkedHashMap<>();

        for (LedgerRow r : rows) {
            long d = r.debitPaise();
            long c = r.creditPaise();
            totalDebit += d;
            totalCredit += c;
            Sum v = byVoucher.computeIfAbsent(r.voucherId(), k -> new Sum());
            v.debit += d; v.credit += c; v.rows++;
            Sum a = byAccount.computeIfAbsent(r.accountCode(), k -> new Sum());
            a.debit += d; a.credit += c; a.name = r.accountName();
        }

        List<VoucherImbalance> voucherImbalances = new ArrayList<>();
        byVoucher.forEach((voucherId, v) -> {
            if (v.debit != v.credit) {
                voucherImbalances.add(new VoucherImbalance(voucherId, v.debit, v.credit, v.debit - v.credit, v.rows));
            }
        });
        voucherImbalances.sort(Comparator.comparingLong((VoucherImbalance x) -> Math.abs(x.difference())).reversed());

        List<TbDifference> tbDifferences = new ArrayList<>();
        if (!tbProvided) {
            voucherImbalances.sort(Comparator.comparingLong((VoucherImbalance x) -> Math.abs(x.difference())).reversed());
            return new ValidationResult(totalDebit, totalCredit, totalDebit == totalCredit,
                    voucherImbalances, tbDifferences, true);
        }
        Map<String, TrialBalanceRow> tbByAccount = new LinkedHashMap<>();
        for (TrialBalanceRow t : tb) tbByAccount.put(t.accountCode(), t);

        byAccount.forEach((accountCode, a) -> {
            TrialBalanceRow t = tbByAccount.get(accountCode);
            long tbDebit = t == null ? 0 : t.debit();
            long tbCredit = t == null ? 0 : t.credit();
            long difference = (a.debit - a.credit) - (tbDebit - tbCredit);
            if (difference != 0) {
                tbDifferences.add(new TbDifference(accountCode, a.name, a.debit, a.credit, tbDebit, tbCredit, difference));
            }
        });
        // TB accounts with movement that never appear in the ledger
        for (TrialBalanceRow t : tb) {
            if (!byAccount.containsKey(t.accountCode()) && (t.debit() != 0 || t.credit() != 0)) {
                tbDifferences.add(new TbDifference(t.accountCode(), t.accountName(),
                        0, 0, t.debit(), t.credit(), -(t.debit() - t.credit())));
            }
        }
        tbDifferences.sort(Comparator.comparingLong((TbDifference x) -> Math.abs(x.difference())).reversed());

        return new ValidationResult(totalDebit, totalCredit, totalDebit == totalCredit,
                voucherImbalances, tbDifferences, tbDifferences.isEmpty());
    }
}
