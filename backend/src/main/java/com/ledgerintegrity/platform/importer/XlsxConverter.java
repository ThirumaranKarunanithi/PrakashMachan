package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Csv;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * BRD §4.1: Excel import. Converts the first sheet of an .xlsx workbook into the same
 * table shape the CSV pipeline consumes — dates become ISO yyyy-MM-dd regardless of
 * the cell's display format, numbers keep full precision (no scientific notation).
 */
public final class XlsxConverter {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private XlsxConverter() {}

    public static Csv.Table toTable(byte[] content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> header = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), width);
                for (int c = 0; c < last; c++) {
                    values.add(cellText(row.getCell(c)));
                }
                if (header.isEmpty()) {
                    // trim trailing empty header cells to fix the table width
                    while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) {
                        values.remove(values.size() - 1);
                    }
                    header = values;
                    width = header.size();
                } else {
                    while (values.size() > width) values.remove(values.size() - 1);
                    while (values.size() < width) values.add("");
                    if (values.stream().anyMatch(v -> !v.isEmpty())) rows.add(values);
                }
            }
            return new Csv.Table(header, rows);
        }
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().format(ISO)
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case FORMULA -> {
                try {
                    yield DateUtil.isCellDateFormatted(cell)
                            ? cell.getLocalDateTimeCellValue().toLocalDate().format(ISO)
                            : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                } catch (Exception e) {
                    yield cell.getRichStringCellValue().getString().trim();
                }
            }
            default -> "";
        };
    }
}
