package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.Lineage;
import com.ledgerintegrity.platform.rules.sta.ActivityDriftCusumRule;
import com.ledgerintegrity.platform.rules.sta.ActivitySpikeRule;
import com.ledgerintegrity.platform.rules.sta.ModifiedZScoreOutlierRule;
import com.ledgerintegrity.platform.rules.sta.RareUserAccountRule;
import com.ledgerintegrity.platform.rules.sta.ThresholdBunchingRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Golden test packs for the statistical layer v2 (integrity-core guide §13.1). */
class StatisticalRulesTest {

    private static final LocalDate CLOSE = LocalDate.of(2025, 3, 31);

    private static LedgerRow row(String voucher, String type, String account, LocalDate txn,
                                 long debitPaise, String source, String user) {
        return new LedgerRow(voucher, type, txn, LocalDateTime.of(txn, java.time.LocalTime.NOON),
                account, "Account " + account,
                debitPaise == 0 ? null : debitPaise, debitPaise == 0 ? 1L : null,
                "test", source, user, null, new Lineage("gl.csv", 2));
    }

    private static Rule.Context ctx(RuleParams params, List<LedgerRow> rows) {
        return new Rule.Context(CLOSE, LocalDate.of(2024, 4, 1), params, Voucher.group(rows),
                List.of(), List.of(), List.of());
    }

    // ---------- STA-01 Modified Z-score ----------

    @Test
    void modifiedZScoreFlagsThePlantedOutlierAndShowsPeerContext() {
        List<LedgerRow> rows = new ArrayList<>();
        // 30 vouchers in one peer group, amounts spread 900-1,900 rupees
        for (int i = 0; i < 30; i++) {
            rows.add(row("N" + i, "Payment", "5001", LocalDate.of(2024, 6, 1).plusDays(i),
                    (900 + i * 33) * 100L, "SYSTEM", "ACCT-1"));
        }
        // the planted outlier: Rs 9.5 crore in a ~Rs 1,400 peer group
        rows.add(row("OUT-1", "Payment", "5001", LocalDate.of(2024, 7, 10), 95_00_00_000_00L, "SYSTEM", "ACCT-1"));

        var findings = new ModifiedZScoreOutlierRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertEquals(1, findings.size());
        var f = findings.get(0);
        assertEquals(List.of("OUT-1"), f.voucherIds());
        assertEquals(Finding.Severity.HIGH, f.severity()); // far beyond 2x threshold
        assertTrue(f.reason().contains("Modified Z-score"));
        assertTrue(f.reason().contains("Median Absolute Deviation"));
        assertTrue(f.reason().contains("peer group"));
    }

    @Test
    void zeroMadPeerGroupWidensOrSkipsInsteadOfDividingByZero() {
        List<LedgerRow> rows = new ArrayList<>();
        // fixed-price peer group: every amount identical -> Median Absolute Deviation = 0
        for (int i = 0; i < 25; i++) {
            rows.add(row("F" + i, "Payment", "5002", LocalDate.of(2024, 6, 1).plusDays(i),
                    1_500_00L, "SYSTEM", "ACCT-1"));
        }
        // no crash, and identical values are not outliers of each other
        var findings = new ModifiedZScoreOutlierRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertTrue(findings.isEmpty());
    }

    // ---------- STA-02 rare user-account ----------

    @Test
    void rareUserAccountFlagsMaterialOneOffPostingByEstablishedUser() {
        List<LedgerRow> rows = new ArrayList<>();
        // ACCT-1 posts 60 routine vouchers to account 5001
        for (int i = 0; i < 60; i++) {
            rows.add(row("R" + i, "Journal", "5001", LocalDate.of(2024, 5, 1).plusDays(i % 28),
                    10_000_00L, "MANUAL", "ACCT-1"));
        }
        // ...then once, materially, to the provisions account
        rows.add(row("RARE-1", "Journal", "2901", LocalDate.of(2025, 2, 10), 5_00_000_00L, "MANUAL", "ACCT-1"));
        // another user regularly posts to 2901, so the ACCOUNT itself is not rare
        for (int i = 0; i < 50; i++) {
            rows.add(row("P" + i, "Journal", "2901", LocalDate.of(2024, 5, 1).plusDays(i % 28),
                    9_000_00L, "MANUAL", "ACCT-2"));
        }

        var findings = new RareUserAccountRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertEquals(1, findings.size());
        assertEquals(List.of("RARE-1"), findings.get(0).voucherIds());
        assertTrue(findings.get(0).reason().contains("ACCT-1"));
        assertTrue(findings.get(0).reason().contains("2901"));
    }

    // ---------- STA-03 threshold bunching ----------

    @Test
    void thresholdBunchingFlagsConcentrationJustBelowApprovalLimit() {
        List<LedgerRow> rows = new ArrayList<>();
        // background population well away from the threshold
        for (int i = 0; i < 100; i++) {
            rows.add(row("B" + i, "Payment", "5001", LocalDate.of(2024, 5, 1).plusDays(i % 28),
                    (5_000 + i * 100) * 100L, "SYSTEM", "ACCT-1"));
        }
        // 8 payments squeezed into the band just under the Rs 50,000 approval threshold
        for (int i = 0; i < 8; i++) {
            rows.add(row("T" + i, "Payment", "5001", LocalDate.of(2024, 8, 1).plusDays(i),
                    (49_000 + i * 100) * 100L, "MANUAL", "ACCT-3"));
        }
        // only one just above it
        rows.add(row("A1", "Payment", "5001", LocalDate.of(2024, 8, 12), 52_000_00L, "SYSTEM", "ACCT-1"));

        var findings = new ThresholdBunchingRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertEquals(1, findings.size());
        var f = findings.get(0);
        assertTrue(f.reason().contains("below the approval threshold"));
        assertTrue(f.voucherIds().contains("T0") && f.voucherIds().contains("T7"));
        assertFalse(f.voucherIds().contains("A1"));
    }

    // ---------- STA-04 activity spike ----------

    @Test
    void activitySpikeFlagsMidYearBurstButNotCloseWindowOrBaselineDays() {
        List<LedgerRow> rows = new ArrayList<>();
        // ~2 manual journals per day through June and July (baseline)
        LocalDate start = LocalDate.of(2024, 6, 1);
        for (int d = 0; d < 60; d++) {
            for (int j = 0; j < 2; j++) {
                rows.add(row("D" + d + "x" + j, "Journal", "5001", start.plusDays(d),
                        5_000_00L, "Manual", "ACCT-1"));
            }
        }
        // the burst: 15 manual journals on one mid-year day
        for (int j = 0; j < 15; j++) {
            rows.add(row("SPIKE" + j, "Journal", "5001", LocalDate.of(2024, 8, 1),
                    20_000_00L, "Manual", "MGR-1"));
        }
        // heavy activity INSIDE the close window -> PET-01 territory, STA-04 must skip
        for (int j = 0; j < 15; j++) {
            rows.add(row("CW" + j, "Journal", "5001", LocalDate.of(2025, 3, 29),
                    20_000_00L, "Manual", "MGR-1"));
        }

        var findings = new ActivitySpikeRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertEquals(1, findings.size());
        var f = findings.get(0);
        assertTrue(f.reason().contains("2024-08-01"));
        assertTrue(f.reason().contains("rolling"));
        assertTrue(f.reason().contains("MGR-1"));
    }

    // ---------- STA-05 CUSUM drift ----------

    @Test
    void cusumFlagsGradualDriftThatNoSingleDayWouldTrigger() {
        List<LedgerRow> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 4, 1);
        // 120 days baseline-quarter-plus: 2/day for 90 days...
        for (int d = 0; d < 90; d++) {
            for (int j = 0; j < 2; j++) {
                rows.add(row("N" + d + "x" + j, "Journal", "5001", start.plusDays(d), 5_000_00L, "Manual", "ACCT-1"));
            }
        }
        // ...then a persistent shift to 5/day - each day modest, the drift unmistakable
        for (int d = 90; d < 130; d++) {
            for (int j = 0; j < 5; j++) {
                rows.add(row("G" + d + "x" + j, "Journal", "5001", start.plusDays(d), 8_000_00L, "Manual", "MGR-1"));
            }
        }

        var findings = new ActivityDriftCusumRule().evaluate(ctx(RuleParams.defaults(), rows));
        assertEquals(1, findings.size());
        var f = findings.get(0);
        assertTrue(f.reason().contains("CUSUM"));
        assertTrue(f.reason().contains("MGR-1"));
        assertTrue(f.reason().contains("2024-06-30") || f.reason().contains("2024-07"),
                "drift should be located near day 90: " + f.reason());
    }

    @Test
    void cusumStaysQuietOnStableActivity() {
        List<LedgerRow> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 4, 1);
        for (int d = 0; d < 130; d++) {
            for (int j = 0; j < 2 + (d % 2); j++) { // 2-3 per day, no drift
                rows.add(row("S" + d + "x" + j, "Journal", "5001", start.plusDays(d), 5_000_00L, "Manual", "ACCT-1"));
            }
        }
        assertTrue(new ActivityDriftCusumRule().evaluate(ctx(RuleParams.defaults(), rows)).isEmpty());
    }
}