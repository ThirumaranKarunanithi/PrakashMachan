package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Csv;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ValidationDeltaTest {

    private final NormalizeService normalize = new NormalizeService();
    private final ValidationService validation = new ValidationService();
    private final DeltaService delta = new DeltaService();

    @Test
    void flagsUnbalancedVouchersAndTbDifferences() {
        String csv = String.join("\n",
                TestData.GL_HEADER,
                "V1,Journal,2024-05-10,,1101,Bank,100.00,,dr,Manual,U1,",
                "V1,Journal,2024-05-10,,2101,Creditors,,90.00,cr short,Manual,U1,"); // voucher off by 10
        NormalizeService.GlResult gl = normalize.normalizeGl(Csv.parse(csv), TestData.clientAProfile(), "t.csv");
        NormalizeService.TbResult tb = normalize.normalizeTb(Csv.parse(
                "account_code,account_name,opening,debit,credit,closing\n"
                        + "1101,Bank,0,100.00,0,100.00\n"
                        + "2101,Creditors,0,0,95.00,-95.00\n"), "tb.csv");

        var v = validation.validate(gl.rows(), tb.rows());
        assertFalse(v.balanced());
        assertEquals(1, v.voucherImbalances().size());
        assertEquals(1000L, v.voucherImbalances().get(0).difference()); // 10 rupees in paise
        assertFalse(v.tbAgrees());
        assertTrue(v.tbDifferences().stream()
                .anyMatch(d -> d.accountCode().equals("2101") && d.difference() == 500L));
    }

    @Test
    void deltaImportSkipsPreviouslyLoadedRecordsAddsNewOnes() {
        String csv = String.join("\n",
                TestData.GL_HEADER,
                "V1,Journal,2024-05-10,,1101,Bank,100.00,,a,Manual,U1,",
                "V1,Journal,2024-05-10,,2101,Creditors,,100.00,a,Manual,U1,");
        var first = normalize.normalizeGl(Csv.parse(csv), TestData.clientAProfile(), "p1.csv");
        var d1 = delta.deltaImport(Set.of(), first.rows());
        assertEquals(2, d1.added().size());

        // same content re-uploaded from a different file -> all skipped
        var again = normalize.normalizeGl(Csv.parse(csv), TestData.clientAProfile(), "p1-reupload.csv");
        var d2 = delta.deltaImport(d1.identities(), again.rows());
        assertEquals(0, d2.added().size());
        assertEquals(2, d2.skipped());

        // identity is content-based, not position-based
        assertEquals(delta.rowIdentity(first.rows().get(0)), delta.rowIdentity(again.rows().get(0)));
    }
}
