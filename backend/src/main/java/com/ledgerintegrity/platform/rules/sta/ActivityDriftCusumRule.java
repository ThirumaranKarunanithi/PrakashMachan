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
 * STA-05: sustained drift in manual-journal activity via CUSUM (guide §4.1).
 * STA-04 catches one-day spikes; CUSUM accumulates SMALL daily deviations above
 * the baseline until a persistent shift becomes undeniable — the pattern a
 * per-day threshold never sees. Baseline = the first quarter of the population's
 * date range; standard k=0.5σ allowance, h=5σ decision limit.
 */
public class ActivityDriftCusumRule implements Rule {

    private static final double K_SIGMA = 0.5;   // slack: ignore deviations below baseline + 0.5σ
    private static final double H_SIGMA = 5.0;   // decision limit
    private static final int MIN_BASELINE_DAYS = 30;
    private static final int MAX_TOKENS = 30;

    @Override public String id() { return "STA-05"; }
    @Override public String name() { return "Sustained manual-journal drift (CUSUM)"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        Map<LocalDate, List<Voucher>> manualByDay = new TreeMap<>();
        for (Voucher v : ctx.vouchers()) {
            if (v.isManualJournal() && v.txnDate() != null) {
                manualByDay.computeIfAbsent(v.txnDate(), k -> new ArrayList<>()).add(v);
            }
        }
        if (manualByDay.isEmpty()) return List.of();
        LocalDate first = manualByDay.keySet().iterator().next();
        LocalDate last = ((TreeMap<LocalDate, List<Voucher>>) manualByDay).lastKey();
        long span = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
        if (span < 4L * MIN_BASELINE_DAYS) return List.of(); // too short for a drift baseline

        // baseline: mean and sd of daily counts over the first quarter (zero days included)
        long baselineDays = span / 4;
        double sum = 0, sumSq = 0;
        for (long i = 0; i < baselineDays; i++) {
            int c = manualByDay.getOrDefault(first.plusDays(i), List.of()).size();
            sum += c;
            sumSq += (double) c * c;
        }
        double mean = sum / baselineDays;
        double sd = Math.max(Math.sqrt(Math.max(0, sumSq / baselineDays - mean * mean)), 0.5);

        // one-sided CUSUM over the remainder
        double s = 0;
        LocalDate driftStart = null, signalDay = null;
        for (long i = baselineDays; i < span; i++) {
            LocalDate day = first.plusDays(i);
            int c = manualByDay.getOrDefault(day, List.of()).size();
            double prev = s;
            s = Math.max(0, s + (c - (mean + K_SIGMA * sd)));
            if (s > 0 && prev == 0) driftStart = day;
            if (s > H_SIGMA * sd) { signalDay = day; break; }
        }
        if (signalDay == null) return List.of();

        // contributing vouchers: the drift window, largest first
        LocalDate from = driftStart == null ? signalDay : driftStart;
        List<Voucher> window = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(signalDay); d = d.plusDays(1)) {
            window.addAll(manualByDay.getOrDefault(d, List.of()));
        }
        List<Voucher> tokens = window.stream()
                .sorted((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()))
                .limit(MAX_TOKENS)
                .toList();
        String users = window.stream().map(Voucher::userId).filter(u -> u != null)
                .collect(Collectors.groupingBy(u -> u, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(u -> u.getKey() + " (" + u.getValue() + ")")
                .collect(Collectors.joining(", "));
        return List.of(new Finding(id(), name(), Finding.Severity.MEDIUM,
                window.stream().mapToLong(Voucher::amountPaise).sum(),
                "Manual-journal activity drifted persistently above its baseline from " + from
                        + ", crossing the CUSUM decision limit on " + signalDay + " (baseline "
                        + String.format("%.1f", mean) + " per day over the first " + baselineDays
                        + " days, sd " + String.format("%.1f", sd) + ", k=" + K_SIGMA + "sigma, h="
                        + H_SIGMA + "sigma). " + window.size() + " journal(s) in the drift window."
                        + " Top posting users: " + (users.isEmpty() ? "(no user data)" : users)
                        + ". Sustained drift often reflects a process change or migration"
                        + " — establish the reason before reading anything into it.",
                tokens.stream().map(Voucher::id).sorted().toList(),
                tokens.stream().limit(10).map(Voucher::sourceRefs).collect(Collectors.joining(" "))));
    }
}
