package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRD §4.1 Excel import: an .xlsx GL (with real date and numeric cells) runs through the
 * same pipeline as CSV, and the manifest hashes the ORIGINAL workbook bytes (DAT-001).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:xlsximporttestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class XlsxImportIntegrationTest {

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService importService;

    @Test
    void xlsxGlImportsWithIsoDatesAndOriginalBytesInManifest() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "XLSX-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        byte[] xlsx = buildGlWorkbook();
        String tbCsv = """
                account_code,account_name,opening,debit,credit,closing
                1101,HDFC Bank,0.00,5000.00,7000.50,-2000.50
                1201,Sundry Debtors,0.00,7000.50,0.00,7000.50
                2101,Sundry Creditors,0.00,0.00,5000.00,-5000.00
                """;

        var result = importService.importXlsxInto(e.getId(),
                new SourceFile("general_ledger.xlsx", xlsx),
                new SourceFile("trial_balance.csv", tbCsv.getBytes(StandardCharsets.UTF_8)),
                TestData.clientAProfile());

        // all 4 rows land, vouchers balance, TB agrees
        assertEquals(4, result.addedRows());
        assertTrue(result.pipeline().validation().balanced());
        assertTrue(result.pipeline().validation().tbAgrees());

        // date cells (displayed dd-MM-yyyy in Excel) were normalised to ISO
        assertEquals(LocalDate.of(2024, 6, 15), result.pipeline().population().get(0).txnDate());
        // numeric cell 7000.50 kept its paise precision
        assertEquals(700_050L, result.pipeline().population().get(2).debitPaise());

        // DAT-001: manifest carries the ORIGINAL workbook bytes, not a converted copy
        var glEntry = result.pipeline().manifest().stream()
                .filter(m -> m.file().equals("general_ledger.xlsx")).findFirst().orElseThrow();
        assertEquals(Checksums.sha256Hex(xlsx), glEntry.sha256());
        assertEquals(xlsx.length, glEntry.bytes());

        // DAT-006: re-importing the same workbook adds nothing
        var again = importService.importXlsxInto(e.getId(),
                new SourceFile("general_ledger.xlsx", xlsx),
                new SourceFile("trial_balance.csv", tbCsv.getBytes(StandardCharsets.UTF_8)),
                TestData.clientAProfile());
        assertEquals(0, again.addedRows());
        assertEquals(4, again.skippedRows());
    }

    /** Two balanced vouchers; txn_date as genuine Excel date cells, amounts as numeric cells. */
    private static byte[] buildGlWorkbook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("GL");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("dd-MM-yyyy"));

            String[] header = TestData.GL_HEADER.split(",");
            Row h = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);

            Object[][] rows = {
                    {"JV-1", "Journal", LocalDate.of(2024, 6, 15), "2024-06-15 10:30", "1101", "HDFC Bank", 5000.00, null, "Receipt", "MANUAL", "u1", ""},
                    {"JV-1", "Journal", LocalDate.of(2024, 6, 15), "2024-06-15 10:30", "2101", "Sundry Creditors", null, 5000.00, "Receipt", "MANUAL", "u1", ""},
                    {"JV-2", "Journal", LocalDate.of(2024, 9, 1), "2024-09-01 16:05", "1201", "Sundry Debtors", 7000.50, null, "Sale", "MANUAL", "u2", ""},
                    {"JV-2", "Journal", LocalDate.of(2024, 9, 1), "2024-09-01 16:05", "1101", "HDFC Bank", null, 7000.50, "Sale", "MANUAL", "u2", ""},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) continue;
                    Cell cell = row.createCell(c);
                    if (v instanceof LocalDate d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateStyle);
                    } else if (v instanceof Double n) {
                        cell.setCellValue(n);
                    } else {
                        cell.setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
