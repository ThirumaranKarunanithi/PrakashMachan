package com.ledgerintegrity.platform.rules.sta;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.List;
import java.util.stream.Collectors;

/**
 * STA-03: threshold bunching (integrity-core guide §3.1) — a population-shape test for
 * amounts concentrated JUST BELOW the approval threshold. Where VP-05 pairs split
 * payments to one payee, this rule sees the aggregate pattern: many vouchers, any
 * payee, sitting in the 90-100%% band under the limit while the band just above is
 * comparatively empty.
 */
public class ThresholdBunchingRule implements Rule {

    private static final int MIN_POPULATION = 100;
    private static final int MIN_BELOW_COUNT = 5;
    private static final double BAND = 0.10;          // 10% band each side of the threshold
    private static final double MIN_RATIO = 2.0;      // below-band must be at least 2x above-band
    private static final int MAX_TOKENS = 50;

    @Override public String id() { return "STA-03"; }
    @Override public String name() { return "Threshold bunching below approval limit"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        if (ctx.vouchers().size() < MIN_POPULATION) return List.of();
        long threshold = ctx.params().approvalThresholdPaise();
        if (threshold <= 0) return List.of();
        long bandLow = Math.round(threshold * (1 - BAND));
        long bandHigh = Math.round(threshold * (1 + BAND));

        List<Voucher> below = ctx.vouchers().stream()
                .filter(v -> v.amountPaise() >= bandLow && v.amountPaise() < threshold)
                .toList();
        long aboveCount = ctx.vouchers().stream()
                .filter(v -> v.amountPaise() >= threshold && v.amountPaise() <= bandHigh)
                .count();

        if (below.size() < MIN_BELOW_COUNT) return List.of();
        double ratio = below.size() / (double) Math.max(aboveCount, 1);
        if (ratio < MIN_RATIO) return List.of();

        List<Voucher> tokens = below.stream()
                .sorted((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()))
                .limit(MAX_TOKENS)
                .toList();
        boolean strong = ratio >= 2 * MIN_RATIO;
        return List.of(new Finding(id(), name(),
                strong ? Finding.Severity.HIGH : Finding.Severity.MEDIUM,
                below.stream().mapToLong(Voucher::amountPaise).sum(),
                below.size() + " voucher(s) sit in the band Rs " + rupees(bandLow) + " - Rs "
                        + rupees(threshold) + " immediately below the approval threshold, against only "
                        + aboveCount + " in the equivalent band above it (" + String.format("%.1f", ratio)
                        + "x concentration). Values shaped just under a limit avoid a control by design"
                        + " — review the approval logic for this band. Largest: "
                        + tokens.stream().limit(5)
                                .map(v -> v.id() + " (Rs " + rupees(v.amountPaise()) + ", " + v.userId() + ")")
                                .collect(Collectors.joining("; "))
                        + ". Legitimate pricing can also cluster; this is a review signal, not a conclusion.",
                tokens.stream().map(Voucher::id).sorted().toList(),
                tokens.stream().limit(10).map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
