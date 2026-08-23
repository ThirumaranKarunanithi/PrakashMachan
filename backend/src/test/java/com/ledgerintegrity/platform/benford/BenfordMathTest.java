package com.ledgerintegrity.platform.benford;

import com.ledgerintegrity.platform.benford.persist.BenfordRun.Conformity;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.DigitTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenfordMathTest {

    @Test
    void digitExtractionIsScaleInvariant() {
        assertEquals("4", BenfordService.digitLabel(4_900_000_00L, DigitTest.FIRST));
        assertEquals("49", BenfordService.digitLabel(4_900_000_00L, DigitTest.FIRST_TWO));
        assertEquals("9", BenfordService.digitLabel(4_900_000_00L, DigitTest.SECOND));
        // same digits regardless of magnitude (paise vs rupees)
        assertEquals("1", BenfordService.digitLabel(123L, DigitTest.FIRST));
        assertEquals("1", BenfordService.digitLabel(123_00L, DigitTest.FIRST));
        assertEquals("12", BenfordService.digitLabel(123_00L, DigitTest.FIRST_TWO));
    }

    @Test
    void terminalPairUsesTheRupeeEndingAndAUniformReference() {
        // Rs 1,234.56 -> rupee value 1234 -> ending "34"
        assertEquals("34", BenfordService.digitLabel(1_234_56L, DigitTest.LAST_TWO));
        // Rs 1,50,000 -> ending "00" (the classic round-amount preference)
        assertEquals("00", BenfordService.digitLabel(1_50_000_00L, DigitTest.LAST_TWO));
        // Rs 49,999 -> ending "99" (just-below-threshold preference)
        assertEquals("99", BenfordService.digitLabel(49_999_00L, DigitTest.LAST_TWO));
        // uniform reference: every ending expects 1%
        assertEquals(1.0, BenfordService.expectedPct("00", DigitTest.LAST_TWO), 0.0001);
        assertEquals(1.0, BenfordService.expectedPct("47", DigitTest.LAST_TWO), 0.0001);
    }

    @Test
    void expectedProportionsMatchTheBenfordFormula() {
        assertEquals(30.10, BenfordService.expectedPct("1", DigitTest.FIRST), 0.01);
        assertEquals(4.58, BenfordService.expectedPct("9", DigitTest.FIRST), 0.01);
        assertEquals(0.88, BenfordService.expectedPct("49", DigitTest.FIRST_TWO), 0.01); // log10(50/49)
        // second-digit expectations sum to ~100 over 0..9
        double sum = 0;
        for (int d = 0; d <= 9; d++) sum += BenfordService.expectedPct(String.valueOf(d), DigitTest.SECOND);
        assertEquals(100.0, sum, 0.01);
    }

    @Test
    void madBandsFollowNigriniThresholds() {
        assertEquals(Conformity.CLOSE, BenfordService.classify(0.004, DigitTest.FIRST));
        assertEquals(Conformity.ACCEPTABLE, BenfordService.classify(0.010, DigitTest.FIRST));
        assertEquals(Conformity.MARGINAL, BenfordService.classify(0.013, DigitTest.FIRST));
        assertEquals(Conformity.NONCONFORMITY, BenfordService.classify(0.020, DigitTest.FIRST));
        assertEquals(Conformity.NONCONFORMITY, BenfordService.classify(0.003, DigitTest.FIRST_TWO));
    }

    @Test
    void secondOrderDifferencesAreSortedPositiveGaps() {
        java.util.List<com.ledgerintegrity.platform.rules.Voucher> vs = new java.util.ArrayList<>();
        long[] amounts = {5_000_00L, 1_000_00L, 3_000_00L, 3_000_00L, 10_000_00L};
        for (int i = 0; i < amounts.length; i++) {
            vs.add(new com.ledgerintegrity.platform.rules.Voucher("V" + i, java.util.List.of(
                    new com.ledgerintegrity.platform.importer.model.LedgerRow("V" + i, "Journal",
                            java.time.LocalDate.of(2024, 6, 1), null, "5001", "A",
                            amounts[i], null, "t", "Manual", "u", null,
                            new com.ledgerintegrity.platform.importer.model.Lineage("gl.csv", 2)))));
        }
        // sorted: 1000, 3000, 3000, 5000, 10000 -> positive gaps 2000, 2000, 5000 (zero gap dropped)
        var diffs = BenfordService.secondOrderDifferences(vs);
        assertEquals(java.util.List.of(2_000_00L, 2_000_00L, 5_000_00L), diffs);
        assertEquals("20", BenfordService.digitLabel(2_000_00L, DigitTest.SECOND_ORDER));
    }
}