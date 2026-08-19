package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.importer.model.LedgerRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** One voucher = the ledger lines sharing a voucher id. Rules mostly reason at this level. */
public record Voucher(String id, List<LedgerRow> lines) {

    public static List<Voucher> group(List<LedgerRow> rows) {
        Map<String, List<LedgerRow>> byId = new LinkedHashMap<>();
        for (LedgerRow r : rows) byId.computeIfAbsent(r.voucherId(), k -> new ArrayList<>()).add(r);
        return byId.entrySet().stream().map(e -> new Voucher(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    public String type() { return lines.get(0).voucherType(); }
    public LocalDate txnDate() { return lines.get(0).txnDate(); }
    public String narration() { return lines.get(0).narration(); }
    public String source() { return lines.get(0).source(); }
    public String userId() { return lines.get(0).userId(); }
    public String reversalOf() { return lines.get(0).reversalOf(); }

    /** Latest creation timestamp across lines, or null if the source supplies none. */
    public LocalDateTime createdAt() {
        LocalDateTime max = null;
        for (LedgerRow r : lines) {
            if (r.createdAt() != null && (max == null || r.createdAt().isAfter(max))) max = r.createdAt();
        }
        return max;
    }

    /** Voucher amount = sum of debit legs, paise. */
    public long amountPaise() {
        return lines.stream().mapToLong(LedgerRow::debitPaise).sum();
    }

    /** Source references for lineage display (DAT-005). */
    public String sourceRefs() {
        return lines.stream().map(r -> r.lineage().toString()).collect(Collectors.joining(" "));
    }

    public boolean isManualJournal() {
        return "Journal".equalsIgnoreCase(type()) && "Manual".equalsIgnoreCase(source() == null ? "" : source());
    }
}
