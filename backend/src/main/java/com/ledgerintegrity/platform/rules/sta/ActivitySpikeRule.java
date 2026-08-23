package com.ledgerintegrity.platform.rules.sta;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * STA-04: manual-journal activity spikes against a ROLLING robust baseline
 * (integrity-core guide §4.1 "rolling median and MAD"). Each day's manual-journal
 * count is compared with the median + MAD of the trailing window, so a mid-year
 * burst stands out against what was normal just before it. Days inside the close
 * window are excluded — PET-01 already covers period-end volume with its own
 * baseline, and one underlying event should not become two findings.
 */
public class ActivitySpikeRule implements Rule {

    private static final int MIN_SPIKE_COUNT = 5;
    private static final int MAX_FINDINGS = 10;
    private static final int MAX_TOKENS = 30;

    @Override public String id() { return "STA-04"; }
    @Override public String name() { return "Manual-journal activity spike vs rolling baseline"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        int window = ctx.params().spikeBaselineDays();
        double multiplier = ctx.params().spikeMadMultiplier();
        LocalDate closeStart = ctx.closeDate() == null ? null
                : ctx.closeDate().minusDays(ctx.params().closeWindowDays());

        Map<LocalDate, List<Voucher>> manualByDay = new TreeMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.isManualJournal() && v.txnDate() != null) {
                manualByDay.computeIfAbsent(v.txnDate(), k -> new ArrayList<>()).add(v);
            }
        }
        if (manualByDay.isEmpty()) return List.of();

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Voucher>> e : manualByDay.entrySet()) {
            if (findings.size() >= MAX_FINDINGS) break;
            LocalDate day = e.getKey();
            int count = e.getValue().size();
            if (count < MIN_SPIKE_COUNT) continue;
            // PET-01 owns the close window
            if (closeStart != null && !day.isBefore(closeStart) && !day.isAfter(ctx.closeDate())) continue;

            // trailing baseline: counts for every calendar day in the window (zeros included)
            double[] baseline = new double[window];
            for (int i = 1; i <= window; i++) {
                List<Voucher> prior = manualByDay.get(day.minusDays(i));
                baseline[i - 1] = prior == null ? 0 : prior.size();
            }
            java.util.Arrays.sort(baseline);
            double median = median(baseline);
            double[] deviations = new double[window];
            for (int i = 0; i < window; i++) deviations[i] = Math.abs(baseline[i] - median);
            java.util.Arrays.sort(deviations);
            double mad = median(deviations);
            double limit = median + multiplier * Math.max(mad, 1.0);
            if (count <= limit) continue;

            List<Voucher> tokens = e.getValue().stream()
                    .sorted((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()))
                    .limit(MAX_TOKENS)
                    .toList();
            String users = e.getValue().stream().map(Voucher::userId)
                    .filter(u -> u != null)
                    .collect(Collectors.groupingBy(u -> u, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(u -> u.getKey() + " (" + u.getValue() + ")")
                    .collect(Collectors.joining(", "));
            findings.add(new Finding(id(), name(), Finding.Severity.MEDIUM,
                    e.getValue().stream().mapToLong(Voucher::amountPaise).sum(),
                    count + " manual journal(s) were posted on " + day + " against a rolling "
                            + window + "-day baseline median of " + String.format("%.0f", median)
                            + " per day (Median Absolute Deviation " + String.format("%.1f", mad)
                            + ", flag limit " + String.format("%.0f", limit) + "). Top posting users: "
                            + (users.isEmpty() ? "(no user data)" : users)
                            + ". A burst of manual activity outside period-end deserves an explanation"
                            + " — it may be a migration, a correction batch, or something to review.",
                    tokens.stream().map(Voucher::id).sorted().toList(),
                    tokens.stream().limit(10).map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
