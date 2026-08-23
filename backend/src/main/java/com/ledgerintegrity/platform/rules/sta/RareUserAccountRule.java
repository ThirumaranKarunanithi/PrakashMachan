package com.ledgerintegrity.platform.rules.sta;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * STA-02: rarity scoring on user-account combinations (integrity-core guide §3.1).
 * A user posting material amounts to an account they otherwise never touch is a
 * classic access-pattern review signal. Rarity is measured against this client's own
 * history, and the historical frequency is shown in the explanation (minimum-support
 * guardrail from the guide). Complements JE-06, which looks at rare ACCOUNT pairings.
 */
public class RareUserAccountRule implements Rule {

    private static final int MIN_POPULATION = 100;
    /** The user must have a real posting history before rarity means anything. */
    private static final int MIN_USER_LINES = 50;
    private static final int MAX_FINDINGS = 25;

    @Override public String id() { return "STA-02"; }
    @Override public String name() { return "Rare user-account combination"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        if (ctx.vouchers().size() < MIN_POPULATION) return List.of();
        int minSupport = ctx.params().rarityMinSupport();
        long materiality = ctx.params().roundAmountThresholdPaise();

        Map<String, Integer> userLineCounts = new LinkedHashMap<>();
        Map<String, List<Voucher>> comboVouchers = new LinkedHashMap<>();
        Map<String, Integer> comboCounts = new LinkedHashMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.userId() == null) continue;
            Set<String> accountsInVoucher = new LinkedHashSet<>();
            for (LedgerRow l : v.lines()) {
                userLineCounts.merge(v.userId(), 1, Integer::sum);
                accountsInVoucher.add(l.accountCode());
            }
            for (String account : accountsInVoucher) {
                String combo = v.userId() + "|" + account;
                comboCounts.merge(combo, 1, Integer::sum);
                comboVouchers.computeIfAbsent(combo, k -> new ArrayList<>()).add(v);
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, Integer> e : comboCounts.entrySet()) {
            if (findings.size() >= MAX_FINDINGS) break;
            if (e.getValue() > minSupport) continue;
            String user = e.getKey().substring(0, e.getKey().indexOf('|'));
            String account = e.getKey().substring(e.getKey().indexOf('|') + 1);
            if (userLineCounts.getOrDefault(user, 0) < MIN_USER_LINES) continue;

            List<Voucher> material = comboVouchers.get(e.getKey()).stream()
                    .filter(v -> v.amountPaise() >= materiality)
                    .toList();
            if (material.isEmpty()) continue;

            boolean privileged = ctx.params().privilegedUsers().contains(user);
            findings.add(new Finding(id(), name(),
                    privileged ? Finding.Severity.HIGH : Finding.Severity.MEDIUM,
                    material.stream().mapToLong(Voucher::amountPaise).max().orElse(0),
                    "User " + user + " posted to account " + account + " in only " + e.getValue()
                            + " voucher(s) across " + userLineCounts.get(user)
                            + " ledger lines by this user — a rare combination for this client."
                            + (privileged ? " The user is on the engagement's privileged list." : "")
                            + " Vouchers: " + material.stream()
                                    .map(v -> v.id() + " (" + v.txnDate() + ", Rs " + rupees(v.amountPaise()) + ")")
                                    .collect(Collectors.joining("; "))
                            + ". A rare combination can be entirely legitimate; it identifies where to look, not what happened.",
                    material.stream().map(Voucher::id).sorted().toList(),
                    material.stream().map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
