package com.ledgerintegrity.platform.rules.vp;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VP-05: several payments to one payee, each just below the approval threshold,
 * within a short window — the group total exceeding the level a single payment
 * would have required (structuring below approval limits).
 */
public class ThresholdSplitRule implements Rule {

    /** A payment counts as "just below" when it is within this fraction under the threshold. */
    private static final double NEAR_FRACTION = 0.90;

    @Override public String id() { return "VP-05"; }
    @Override public String name() { return "Payments split below approval threshold"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        long threshold = ctx.params().approvalThresholdPaise();
        int windowDays = ctx.params().splitWindowDays();

        Map<String, List<Voucher>> byPayee = new LinkedHashMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (!"Payment".equalsIgnoreCase(v.type())) continue;
            long amt = v.amountPaise();
            if (amt >= threshold || amt < (long) (threshold * NEAR_FRACTION)) continue;
            byPayee.computeIfAbsent(payee(v.narration()), k -> new ArrayList<>()).add(v);
        }

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<Voucher>> e : byPayee.entrySet()) {
            List<Voucher> list = e.getValue();
            list.sort(Comparator.comparing(Voucher::txnDate));
            for (int i = 0; i < list.size(); i++) {
                Voucher anchor = list.get(i);
                List<Voucher> group = list.stream()
                        .filter(v -> Math.abs(ChronoUnit.DAYS.between(anchor.txnDate(), v.txnDate())) <= windowDays)
                        .toList();
                long total = group.stream().mapToLong(Voucher::amountPaise).sum();
                if (group.size() >= 2 && total > threshold) {
                    findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                            total,
                            group.size() + " payments to " + e.getKey() + " within " + windowDays
                                    + " days, each just below the Rs " + rupees(threshold)
                                    + " approval threshold: "
                                    + group.stream().map(v -> v.id() + " " + v.txnDate() + " Rs " + rupees(v.amountPaise()))
                                    .collect(Collectors.joining("; "))
                                    + ". Group total Rs " + rupees(total) + " exceeds the threshold.",
                            group.stream().map(Voucher::id).sorted().toList(),
                            group.stream().map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
                    break; // one case per payee
                }
            }
        }
        return findings;
    }

    /** Payee inferred from payment narration: strip prefix and trailing payment reference. */
    private static String payee(String narration) {
        return narration
                .replaceFirst("^(Payment to |Advance to |Paid to )", "")
                .replaceFirst(" (NEFT|RTGS|IMPS|UPI|UTR|chq|CHQ).*$", "")
                .trim();
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
