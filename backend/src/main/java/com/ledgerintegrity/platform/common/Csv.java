package com.ledgerintegrity.platform.common;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180-ish CSV parser. No dependencies. */
public final class Csv {

    public record Table(List<String> header, List<List<String>> rows) {}

    private Csv() {}

    public static Table parse(String text) {
        String src = text.replace("\r\n", "\n").replace('\r', '\n');
        List<List<String>> rows = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> row = new ArrayList<>();
        boolean inQuotes = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length() && src.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else if (c == '"') inQuotes = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\n') {
                row.add(field.toString()); field.setLength(0);
                if (row.size() > 1 || !row.get(0).isEmpty()) rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        List<String> header = rows.isEmpty() ? List.of() : rows.remove(0);
        return new Table(header, rows);
    }

    /** Serialise rows to CSV with proper quoting. */
    public static String serialize(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, header);
        for (List<String> r : rows) appendRow(sb, r);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) sb.append(',');
            String s = row.get(i) == null ? "" : row.get(i);
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                sb.append('"').append(s.replace("\"", "\"\"")).append('"');
            } else sb.append(s);
        }
        sb.append('\n');
    }
}
