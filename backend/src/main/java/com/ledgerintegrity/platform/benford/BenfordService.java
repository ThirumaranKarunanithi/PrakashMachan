package com.ledgerintegrity.platform.benford;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerintegrity.platform.benford.persist.BenfordRun;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Conformity;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.DigitTest;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Population;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Suitability;
import com.ledgerintegrity.platform.benford.persist.BenfordRunRepository;
import com.ledgerintegrity.platform.importer.persist.LedgerEntry;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.ExceptionService;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Voucher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BRD §16: Benford analysis as one sensor inside the risk engine — never an automatic
 * fraud detector. The suitability gate (BEN-002) runs BEFORE any conformity score;
 * unsuitable populations get a descriptive table only and contribute zero risk points.
 * A digit anomaly on a suitable population raises ONE neutral, medium-weight exception
 * through the shared pipeline so it consolidates with independent signals (BEN-009/16.7).
 */
@Service
public class BenfordService {

    static final String VERSION = "benford-0.1.0";
    /** Methodology minimums per test (BEN-004): below this, no formal conformity score. */
    static final int MIN_FIRST = 300;
    static final int MIN_SECOND = 300;
    static final int MIN_FIRST_TWO = 1000;
    /** Values should span at least this many orders of magnitude. */
    static final double MIN_ORDERS_OF_MAGNITUDE = 2.0;
    /** A single repeated value above this share suggests fixed pricing. */
    static final double DOMINANT_VALUE_SHARE = 0.20;

    public record Bucket(String digit, int observed, double observedPct, double expectedPct, int excess) {}

    public record RunOutcome(BenfordRun run, List<Bucket> buckets) {}

    private final LedgerEntryRepository entries;
    private final BenfordRunRepository runs;
    private final ExceptionService exceptionService;
    private final ObjectMapper objectMapper;

    public BenfordService(LedgerEntryRepository entries, BenfordRunRepository runs,
                          ExceptionService exceptionService, ObjectMapper objectMapper) {
        this.entries = entries;
        this.runs = runs;
        this.exceptionService = exceptionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RunOutcome run(UUID engagementId, Population population, DigitTest digitTest,
                          boolean overrideSuitability, String overrideReason) {
        if (overrideSuitability && (overrideReason == null || overrideReason.isBlank())) {
            throw new IllegalArgumentException("Overriding the suitability gate requires a recorded reason (BEN-011).");
        }

        List<Voucher> all = Voucher.group(entries.findByEngagementId(engagementId).stream()
                .map(LedgerEntry::toRow).toList());
        List<Voucher> selected = all.stream().filter(v -> matches(population, v)).toList();

        // BEN-003: exclusions are counted and reported, never silently dropped
        int zeros = 0, negatives = 0, reversals = 0;
        List<Voucher> eligible = new ArrayList<>();
        long eligibleValue = 0;
        for (Voucher v : selected) {
            long amt = v.amountPaise();
            if (v.reversalOf() != null) { reversals++; continue; }
            if (amt == 0) { zeros++; continue; }
            if (amt < 0) { negatives++; continue; }
            eligible.add(v);
            eligibleValue += amt;
        }

        BenfordRun run = new BenfordRun(UUID.randomUUID(), engagementId, population, digitTest,
                Instant.now(), paramsJson(digitTest));
        run.setExclusions(eligible.size(), eligibleValue, zeros, negatives, reversals);

        // BEN-002: suitability gate before any conformity result
        List<String> reasons = new ArrayList<>();
        Suitability verdict = assess(eligible, digitTest, reasons);
        run.setSuitability(verdict, String.join(" | ", reasons), overrideSuitability,
                overrideSuitability ? overrideReason.trim() : null);

        List<Bucket> buckets = computeBuckets(eligible, digitTest);

        boolean assess = verdict != Suitability.NOT_SUITABLE || overrideSuitability;
        Double mad = null;
        Conformity conformity = Conformity.NOT_ASSESSED;
        UUID exceptionId = null;
        Map<String, Object> extras = new LinkedHashMap<>();

        if (assess && !eligible.isEmpty()) {
            mad = buckets.stream().mapToDouble(b -> Math.abs(b.observedPct() - b.expectedPct()) / 100.0)
                    .average().orElse(0);
            conformity = classify(mad, digitTest);

            Bucket top = buckets.stream().max(Comparator.comparingInt(Bucket::excess)).orElse(null);
            if (top != null && top.excess() > 0) {
                extras.put("topExcessDigit", top.digit());
                extras.put("topContributorsByUser", contributorsByUser(eligible, digitTest, top.digit()));
            }

            if (conformity == Conformity.MARGINAL || conformity == Conformity.NONCONFORMITY) {
                exceptionId = raiseException(engagementId, run, mad, conformity, top, eligible);
            }
        }
        extras.put("buckets", buckets);
        run.setOutcome(mad, conformity, toJson(extras), exceptionId);
        runs.save(run);
        return new RunOutcome(run, buckets);
    }

    /** BEN-006: drill from a digit bucket to the exact contributing source vouchers. */
    public List<Voucher> drilldown(BenfordRun run, String digit) {
        List<Voucher> all = Voucher.group(entries.findByEngagementId(run.getEngagementId()).stream()
                .map(LedgerEntry::toRow).toList());
        return all.stream()
                .filter(v -> matches(run.getPopulation(), v))
                .filter(v -> v.reversalOf() == null && v.amountPaise() > 0)
                .filter(v -> digitLabel(v.amountPaise(), run.getDigitTest()).equals(digit))
                .sorted(Comparator.comparingLong(Voucher::amountPaise).reversed())
                .limit(500)
                .toList();
    }

    // ---------- population / digits ----------

    private static boolean matches(Population p, Voucher v) {
        return switch (p) {
            case MANUAL_JOURNALS -> v.isManualJournal();
            case ALL_VOUCHERS -> true;
            case PAYMENTS -> "Payment".equalsIgnoreCase(v.type());
            case PURCHASES -> "Purchase".equalsIgnoreCase(v.type());
            case SALES -> "Sales".equalsIgnoreCase(v.type());
        };
    }

    /** Leading digits of the amount; identical whether expressed in paise or rupees. */
    static String digitLabel(long amountPaise, DigitTest test) {
        double x = amountPaise;
        while (x >= 10) x /= 10;
        while (x < 1) x *= 10;
        int firstTwo = (int) (x * 10); // 10..99
        return switch (test) {
            case FIRST -> String.valueOf(firstTwo / 10);
            case SECOND -> String.valueOf(firstTwo % 10);
            case FIRST_TWO -> String.valueOf(firstTwo);
        };
    }

    /** Expected Benford proportion for a digit label under the given test. */
    static double expectedPct(String digit, DigitTest test) {
        int d = Integer.parseInt(digit);
        return switch (test) {
            case FIRST -> Math.log10(1.0 + 1.0 / d) * 100;
            case FIRST_TWO -> Math.log10(1.0 + 1.0 / d) * 100;
            case SECOND -> {
                double p = 0;
                for (int d1 = 1; d1 <= 9; d1++) p += Math.log10(1.0 + 1.0 / (10 * d1 + d));
                yield p * 100;
            }
        };
    }

    private static List<String> labels(DigitTest test) {
        List<String> out = new ArrayList<>();
        switch (test) {
            case FIRST -> { for (int d = 1; d <= 9; d++) out.add(String.valueOf(d)); }
            case SECOND -> { for (int d = 0; d <= 9; d++) out.add(String.valueOf(d)); }
            case FIRST_TWO -> { for (int d = 10; d <= 99; d++) out.add(String.valueOf(d)); }
        }
        return out;
    }

    private static List<Bucket> computeBuckets(List<Voucher> eligible, DigitTest test) {
        Map<String, Integer> observed = new LinkedHashMap<>();
        for (String l : labels(test)) observed.put(l, 0);
        for (Voucher v : eligible) observed.merge(digitLabel(v.amountPaise(), test), 1, Integer::sum);
        int n = eligible.size();
        List<Bucket> buckets = new ArrayList<>();
        for (String l : labels(test)) {
            int obs = observed.get(l);
            double expPct = expectedPct(l, test);
            double obsPct = n == 0 ? 0 : obs * 100.0 / n;
            int excess = (int) Math.round(obs - (expPct / 100.0) * n);
            buckets.add(new Bucket(l, obs, round2(obsPct), round2(expPct), Math.max(excess, 0)));
        }
        return buckets;
    }

    // ---------- suitability & conformity ----------

    private static Suitability assess(List<Voucher> eligible, DigitTest test, List<String> reasons) {
        int min = switch (test) {
            case FIRST -> MIN_FIRST;
            case SECOND -> MIN_SECOND;
            case FIRST_TWO -> MIN_FIRST_TWO;
        };
        if (eligible.size() < min) {
            reasons.add("Population of " + eligible.size() + " eligible amounts is below the methodology minimum of "
                    + min + " for this test — descriptive view only, no conformity score.");
            return Suitability.NOT_SUITABLE;
        }
        boolean caution = false;
        long max = eligible.stream().mapToLong(Voucher::amountPaise).max().orElse(1);
        long minV = eligible.stream().mapToLong(Voucher::amountPaise).min().orElse(1);
        double orders = Math.log10((double) max / minV);
        if (orders < MIN_ORDERS_OF_MAGNITUDE) {
            reasons.add(String.format("Values span only %.1f orders of magnitude — narrow ranges weaken Benford expectations.", orders));
            caution = true;
        }
        Map<Long, Integer> byValue = new LinkedHashMap<>();
        for (Voucher v : eligible) byValue.merge(v.amountPaise(), 1, Integer::sum);
        int dominant = byValue.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (dominant > eligible.size() * DOMINANT_VALUE_SHARE) {
            reasons.add("A single repeated value accounts for " + Math.round(dominant * 100.0 / eligible.size())
                    + "% of the population — fixed pricing weakens Benford expectations.");
            caution = true;
        }
        if (!caution) reasons.add("Adequate population size, value span and value diversity.");
        return caution ? Suitability.SUITABLE_WITH_CAUTION : Suitability.SUITABLE;
    }

    /** Nigrini MAD bands per test. */
    static Conformity classify(double mad, DigitTest test) {
        double[] t = switch (test) {
            case FIRST -> new double[]{0.006, 0.012, 0.015};
            case SECOND -> new double[]{0.008, 0.010, 0.012};
            case FIRST_TWO -> new double[]{0.0012, 0.0018, 0.0022};
        };
        if (mad < t[0]) return Conformity.CLOSE;
        if (mad < t[1]) return Conformity.ACCEPTABLE;
        if (mad < t[2]) return Conformity.MARGINAL;
        return Conformity.NONCONFORMITY;
    }

    // ---------- exception (BEN-009/010) ----------

    private UUID raiseException(UUID engagementId, BenfordRun run, double mad, Conformity conformity,
                                Bucket top, List<Voucher> eligible) {
        List<Voucher> contributors = top == null ? List.of()
                : eligible.stream()
                .filter(v -> digitLabel(v.amountPaise(), run.getDigitTest()).equals(top.digit()))
                .sorted(Comparator.comparingLong(Voucher::amountPaise).reversed())
                .limit(15)
                .toList();
        // A dispersion statistic is a review signal, not a monetary claim: the exception
        // carries NO rupee exposure. The contributor amounts stay in the drill-down.
        long exposure = 0;
        String reason = "Digit distribution of the " + run.getPopulation() + " population ("
                + run.getEligibleCount() + " amounts, " + run.getDigitTest() + " digit test) deviates from the "
                + "Benford expectation (MAD " + String.format("%.4f", mad) + ", " + conformity + ")."
                + (top == null ? "" : " Amounts beginning with " + top.digit() + " occur " + top.excess()
                + " time(s) more than expected.")
                + " This is a statistical review signal, not a conclusion; corroborate with timing, user,"
                + " approval and document evidence.";
        Finding f = new Finding("BEN-01", "Benford digit anomaly", Finding.Severity.MEDIUM, exposure, reason,
                contributors.isEmpty()
                        ? List.of("BENFORD:" + run.getPopulation() + ":" + run.getDigitTest())
                        : contributors.stream().map(Voucher::id).sorted().toList(),
                contributors.stream().limit(5).map(Voucher::sourceRefs)
                        .reduce((a, b) -> a + " " + b).orElse("benford-run:" + run.getId()));
        ExceptionService.RaiseResult raised = exceptionService.raise(engagementId, run.getId(), List.of(f));
        return raised.created().isEmpty() ? null : raised.created().get(0).getId();
    }

    // ---------- misc ----------

    private String paramsJson(DigitTest test) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", VERSION);
        p.put("formula", "P(d) = log10(1 + 1/d)");
        p.put("minPopulation", Map.of("FIRST", MIN_FIRST, "SECOND", MIN_SECOND, "FIRST_TWO", MIN_FIRST_TWO));
        p.put("minOrdersOfMagnitude", MIN_ORDERS_OF_MAGNITUDE);
        p.put("dominantValueShare", DOMINANT_VALUE_SHARE);
        p.put("madBands", "Nigrini");
        p.put("test", test.name());
        return toJson(p);
    }

    private List<Map<String, Object>> contributorsByUser(List<Voucher> eligible, DigitTest test, String digit) {
        Map<String, Integer> byUser = new LinkedHashMap<>();
        for (Voucher v : eligible) {
            if (!digitLabel(v.amountPaise(), test).equals(digit)) continue;
            byUser.merge(v.userId() == null ? "unknown" : v.userId(), 1, Integer::sum);
        }
        return byUser.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> Map.<String, Object>of("user", e.getKey(), "count", e.getValue()))
                .toList();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise Benford result", e);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
