package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JET-004 / JE-05: duplicate and near-duplicate manual journals — the same amount
 * moving through the same accounts under different voucher numbers. Linked reversals
 * are excluded (they are JE-09's story, not a duplicate).
 */
public class DuplicateJournalRule implements Rule {

    @Override public String id() { return "JE-05"; }
    @Override public String name() { return "Duplicate / near-duplicate manual journal"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        // vouchers that participate in a linked reversal are not duplicates
        Set<String> reversalLinked = new HashSet<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.reversalOf() != null) {
                reversalLinked.add(v.id());
                reversalLinked.add(v.reversalOf());
            }
        }

        Map<String, List<Voucher>> byKey = new LinkedHashMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (!v.isManualJournal() || reversalLinked.contains(v.id())) continue;
            byKey.computeIfAbsent(v.amountPaise() + "|" + signature(v), k -> new ArrayList<>()).add(v);
        }

        List<Finding> findings = new ArrayList<>();
        for (List<Voucher> group : byKey.values()) {
            if (group.stream().map(Voucher::id).distinct().count() < 2) continue;
            Voucher first = group.get(0);
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    first.amountPaise(),
                    "Manual journals " + group.stream()
                            .map(v -> v.id() + " (" + v.txnDate() + ", " + v.userId() + ")")
                            .collect(Collectors.joining(" and "))
                            + " post the identical amount of Rs " + rupees(first.amountPaise())
                            + " through the same accounts — possible double posting.",
                    group.stream().map(Voucher::id).distinct().sorted().limit(10).toList(),
                    group.stream().map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
        }
        return findings;
    }

    static String signature(Voucher v) {
        String debits = v.lines().stream().filter(l -> l.debit() != null)
                .map(LedgerRow::accountCode).sorted().distinct().collect(Collectors.joining("+"));
        String credits = v.lines().stream().filter(l -> l.credit() != null)
                .map(LedgerRow::accountCode).sorted().distinct().collect(Collectors.joining("+"));
        return debits + "->" + credits;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
