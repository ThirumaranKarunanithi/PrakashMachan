package com.ledgerintegrity.platform.importer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** DAT-004: reusable column-mapping profile, saved per client/system. */
public record MappingProfile(
        String name,
        SourceType sourceType,
        String description,
        DateFormat dateFormat,
        /** standard field -> source column header */
        Map<StandardField, String> fieldMap
) {
    public enum SourceType { CSV, TALLY_XML, XLSX }

    public enum StandardField {
        VOUCHER_ID, VOUCHER_TYPE, TXN_DATE, CREATED_AT,
        ACCOUNT_CODE, ACCOUNT_NAME, DEBIT, CREDIT,
        NARRATION, SOURCE, USER_ID, REVERSAL_OF
    }

    public enum DateFormat {
        ISO("uuuu-MM-dd"), DMY_DASH("dd-MM-uuuu"), DMY_SLASH("dd/MM/uuuu");

        private final DateTimeFormatter formatter;

        DateFormat(String pattern) {
            this.formatter = DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
        }

        public DateTimeFormatter formatter() { return formatter; }
    }

    public static final Set<StandardField> REQUIRED_FIELDS = Set.of(
            StandardField.VOUCHER_ID, StandardField.VOUCHER_TYPE, StandardField.TXN_DATE,
            StandardField.ACCOUNT_CODE, StandardField.ACCOUNT_NAME, StandardField.NARRATION
    );

    /** Validate this profile against an actual file header. Returns human-readable problems. */
    public List<String> checkAgainstHeader(List<String> header) {
        List<String> problems = new ArrayList<>();
        Set<String> cols = Set.copyOf(header);
        for (StandardField f : REQUIRED_FIELDS) {
            if (fieldMap.get(f) == null || fieldMap.get(f).isBlank()) {
                problems.add("Profile does not map required field \"" + f + "\".");
            }
        }
        if (fieldMap.get(StandardField.DEBIT) == null && fieldMap.get(StandardField.CREDIT) == null) {
            problems.add("Profile maps neither DEBIT nor CREDIT.");
        }
        fieldMap.forEach((field, col) -> {
            if (col != null && !col.isBlank() && !cols.contains(col)) {
                problems.add("Mapped column \"" + col + "\" (for " + field + ") not found in file header.");
            }
        });
        return problems;
    }

    // ---------- boundary parsers ----------

    /** Parse a source date per this profile's format, or null if invalid. */
    public LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim(), dateFormat.formatter());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static final DateTimeFormatter TS_MINUTES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TS_SECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /** Parse "YYYY-MM-DD HH:mm[:ss]" (space or T separator), or null if invalid. */
    public static LocalDateTime parseTimestamp(String value) {
        String v = value.trim().replace('T', ' ');
        for (DateTimeFormatter f : new DateTimeFormatter[]{TS_MINUTES, TS_SECONDS}) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    private static final Pattern AMOUNT = Pattern.compile("^-?\\d+(\\.\\d{1,2})?$");

    /**
     * Parse a rupee amount string into integer paise.
     * Accepts optional thousands separators and up to 2 decimals.
     * Returns null for blank; throws NumberFormatException for garbage (caller records a quality issue).
     */
    public static Long parseAmountPaise(String value) {
        String v = value.trim();
        if (v.isEmpty()) return null;
        String cleaned = v.replace(",", "");
        if (!AMOUNT.matcher(cleaned).matches()) throw new NumberFormatException(value);
        boolean negative = cleaned.startsWith("-");
        String abs = negative ? cleaned.substring(1) : cleaned;
        String[] parts = abs.split("\\.", 2);
        long whole = Long.parseLong(parts[0]);
        long frac = parts.length == 2 ? Long.parseLong((parts[1] + "00").substring(0, 2)) : 0;
        long paise = whole * 100 + frac;
        return negative ? -paise : paise;
    }
}
