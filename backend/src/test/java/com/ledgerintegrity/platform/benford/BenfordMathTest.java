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
}
