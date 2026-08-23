package com.ledgerintegrity.platform.rules.sta;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.rules.Voucher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STA-01: peer-group amount outliers via Median Absolute Deviation and Modified Z-score
 * (integrity-core guide §3.1/3.2). A voucher's amount is compared against the robust
 * normal range of its own peer group (voucher type + primary debit account) on a log
 * scale, so a handful of extreme values cannot distort the benchmark.
 *
 * Median Absolute Deviation here is the PEER-GROUP dispersion measure — deliberately
 * distinct from the population-level "Benford MAD" conformity statistic (guide §3.2).
 * Zero-MAD safeguard: identical peer values make the score incalculable; the rule then
 * widens the peer group to voucher type alone, and skips (never divides by zero) if
 * dispersion is still zero.
 */
public class ModifiedZScoreOutlierRule implements Rule {

    private static final int MIN_PEER_GROUP = 20;
    private static final int MAX_FINDINGS = 25;
    private static final double MZS_CONSTANT = 0.6745;

    @Override public String id() { return "STA-01"; }
    @Override public String name() { return "Peer-group amount outlier (Modified Z-score)"; }

    private record Scored(Voucher voucher, String peerKey, int peerSize,
                          long medianPaise, double madLog, double score, boolean widened) {}

    @Override
    public List<Finding> evaluate(Context ctx) {
        double threshold = ctx.params().modifiedZThreshold();

        Map<String, List<Voucher>> byPeer = new LinkedHashMap<>();
        Map<String, List<Voucher>> byType = new LinkedHashMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.amountPaise() <= 0) continue;
            byPeer.computeIfAbsent(peerKey(v), k -> new ArrayList<>()).add(v);
            byType.computeIfAbsent(v.type(), k -> new ArrayList<>()).add(v);
        }

        List<Scored> outliers = new ArrayList<>();
        for (Map.Entry<String, List<Voucher>> e : byPeer.entrySet()) {
            List<Voucher> peers = e.getValue();
            if (peers.size() < MIN_PEER_GROUP) continue;
            double[] stats = medianAndMad(peers);
            boolean widened = false;
            List<Voucher> benchmark = peers;
            if (stats[1] == 0) {
                // zero-MAD safeguard: widen to voucher type
                benchmark = byType.get(peers.get(0).type());
                if (benchmark == null || benchmark.size() < MIN_PEER_GROUP) continue;
                stats = medianAndMad(benchmark);
                widened = true;
                if (stats[1] == 0) continue; // still no dispersion — score not calculable
            }
            for (Voucher v : peers) {
                double score = MZS_CONSTANT * (log(v.amountPaise()) - stats[0]) / stats[1];
                // Flag the HIGH side only: an amount far ABOVE its peers carries exposure;
                // unusually small amounts are statistically true but rarely audit-relevant,
                // and reporting them floods the review queue (guide: rank candidates, not noise).
                if (score >= threshold) {
                    outliers.add(new Scored(v, e.getKey(), benchmark.size(),
                            Math.round(Math.expm1(stats[0]) * 100), stats[1], score, widened));
                }
            }
        }

        outliers.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Finding> findings = new ArrayList<>();
        for (Scored s : outliers.subList(0, Math.min(outliers.size(), MAX_FINDINGS))) {
            boolean extreme = s.score() >= 2 * threshold;
            findings.add(new Finding(id(), name(),
                    extreme ? Finding.Severity.HIGH : Finding.Severity.MEDIUM,
                    s.voucher().amountPaise(),
                    "Voucher " + s.voucher().id() + " (Rs " + rupees(s.voucher().amountPaise())
                            + ", " + s.voucher().txnDate() + ", " + s.voucher().userId()
                            + ") has a Modified Z-score of " + String.format("%.2f", s.score())
                            + " against its peer group [" + s.peerKey() + "] of " + s.peerSize()
                            + " vouchers (peer median Rs " + rupees(s.medianPaise())
                            + ", log-scale Median Absolute Deviation " + String.format("%.4f", s.madLog())
                            + ", threshold " + String.format("%.1f", ctx.params().modifiedZThreshold()) + ")."
                            + (s.widened() ? " Peer group was widened to the voucher type because the"
                            + " original peers had zero dispersion." : "")
                            + " An unusual amount is a review candidate, not a conclusion.",
                    List.of(s.voucher().id()),
                    s.voucher().sourceRefs()));
        }
        return findings;
    }

    /** Peer group: voucher type + the account of the first debit leg. */
    static String peerKey(Voucher v) {
        String account = v.lines().stream()
                .filter(l -> l.debitPaise() > 0)
                .map(l -> l.accountCode())
                .findFirst().orElse("(none)");
        return v.type() + "|" + account;
    }

    /** Returns {median, medianAbsoluteDeviation} of log1p(rupee amounts). */
    private static double[] medianAndMad(List<Voucher> vouchers) {
        double[] logs = vouchers.stream().mapToDouble(v -> log(v.amountPaise())).sorted().toArray();
        double median = median(logs);
        double[] deviations = new double[logs.length];
        for (int i = 0; i < logs.length; i++) deviations[i] = Math.abs(logs[i] - median);
        java.util.Arrays.sort(deviations);
        return new double[]{median, median(deviations)};
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static double log(long paise) {
        return Math.log1p(paise / 100.0);
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
