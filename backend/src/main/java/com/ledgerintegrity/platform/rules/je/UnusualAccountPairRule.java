package com.ledgerintegrity.platform.rules.je;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JET-005 / JE-06: unusual account combinations — a debit/credit account pairing that
 * is rare for THIS client's own posting history. The rarity benchmark is shown in the
 * explanation (BRD: "the explanation shows why the combination is unusual"); industry
 * patterns and firm-level configuration are the later phase.
 */
public class UnusualAccountPairRule implements Rule {

    /** A signature seen this many times or fewer is "rare" for the client. */
    private static final int MAX_RARE_OCCURRENCES = 2;
    /** Population must be at least this large for rarity to mean anything. */
    private static final int MIN_POPULATION = 100;

    @Override public String id() { return "JE-06"; }
    @Override public String name() { return "Unusual account combination"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        if (ctx.vouchers().size() < MIN_POPULATION) return List.of();

        Map<String, List<Voucher>> bySignature = new LinkedHashMap<>();
        for (Voucher v : ctx.vouchers()) {
            bySignature.computeIfAbsent(DuplicateJournalRule.signature(v), k -> new ArrayList<>()).add(v);
        }

        long materiality = ctx.params().roundAmountThresholdPaise();
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<Voucher>> e : bySignature.entrySet()) {
            List<Voucher> group = e.getValue();
            if (group.size() > MAX_RARE_OCCURRENCES) continue;
            List<Voucher> material = group.stream()
                    .filter(v -> v.amountPaise() >= materiality)
                    .toList();
            if (material.isEmpty()) continue;
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    material.stream().mapToLong(Voucher::amountPaise).max().orElse(0),
                    "The account combination [" + e.getKey() + "] appears only " + group.size()
                            + " time(s) across this client's " + ctx.vouchers().size()
                            + " vouchers — rare for this client. Vouchers: "
                            + material.stream().map(v -> v.id() + " (" + v.txnDate() + ", Rs "
                                    + rupees(v.amountPaise()) + ", " + v.userId() + ")")
                            .collect(Collectors.joining("; ")) + ".",
                    material.stream().map(Voucher::id).sorted().toList(),
                    material.stream().map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
