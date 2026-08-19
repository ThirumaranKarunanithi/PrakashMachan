package com.ledgerintegrity.platform.bank;

import com.ledgerintegrity.platform.bank.persist.BankLedgerLine;
import com.ledgerintegrity.platform.bank.persist.BankLedgerLineRepository;
import com.ledgerintegrity.platform.bank.persist.BankStatementLine;
import com.ledgerintegrity.platform.bank.persist.BankStatementLineRepository;
import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.MappingProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports structured bank statements and the books' bank ledger (BKR-001; MVP: fixed
 * standard CSV headers, ISO dates — PDF extraction is explicitly a later phase, BKR-007).
 *
 * Expected headers —
 *  bank statement: date, narration, reference, debit, credit, balance
 *  bank ledger:    date, voucher_id, reference, debit, credit, narration
 */
@Service
public class BankImportService {

    public record ImportOutcome(int totalRows, int added, int skipped, List<String> problems) {}

    private final BankStatementLineRepository statements;
    private final BankLedgerLineRepository ledger;

    public BankImportService(BankStatementLineRepository statements, BankLedgerLineRepository ledger) {
        this.statements = statements;
        this.ledger = ledger;
    }

    @Transactional
    public ImportOutcome importStatement(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table, List.of("date", "narration", "reference"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(statements.findIdentityHashes(engagementId));
        List<BankStatementLine> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            LocalDate date = date(cell(r, idx, "date"), row, problems);
            if (date == null) continue;
            long debit = money(cell(r, idx, "debit"), row, "debit", problems);
            long credit = money(cell(r, idx, "credit"), row, "credit", problems);
            String ref = cell(r, idx, "reference");
            Long balance = idx.containsKey("balance") && !cell(r, idx, "balance").isEmpty()
                    ? money(cell(r, idx, "balance"), row, "balance", problems) : null;
            String hash = Checksums.sha256Hex(String.join("|", date.toString(), ref,
                    String.valueOf(debit), String.valueOf(credit), cell(r, idx, "narration")));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new BankStatementLine(engagementId, hash, date, cell(r, idx, "narration"),
                    ref, debit, credit, balance, fileName, row));
        }
        statements.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    @Transactional
    public ImportOutcome importLedger(UUID engagementId, String fileName, byte[] content) {
        Csv.Table table = Csv.parse(new String(content, StandardCharsets.UTF_8));
        List<String> problems = checkHeader(table, List.of("date", "voucher_id", "reference"));
        if (!problems.isEmpty()) return new ImportOutcome(table.rows().size(), 0, 0, problems);

        Map<String, Integer> idx = index(table);
        Set<String> existing = new HashSet<>(ledger.findIdentityHashes(engagementId));
        List<BankLedgerLine> toAdd = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> r = table.rows().get(i);
            int row = i + 2;
            LocalDate date = date(cell(r, idx, "date"), row, problems);
            if (date == null) continue;
            long debit = money(cell(r, idx, "debit"), row, "debit", problems);
            long credit = money(cell(r, idx, "credit"), row, "credit", problems);
            String ref = cell(r, idx, "reference");
            String voucher = cell(r, idx, "voucher_id");
            String hash = Checksums.sha256Hex(String.join("|", date.toString(), voucher, ref,
                    String.valueOf(debit), String.valueOf(credit)));
            if (!existing.add(hash)) { skipped++; continue; }
            toAdd.add(new BankLedgerLine(engagementId, hash, date, voucher, ref, debit, credit,
                    cell(r, idx, "narration"), fileName, row));
        }
        ledger.saveAll(toAdd);
        return new ImportOutcome(table.rows().size(), toAdd.size(), skipped, problems);
    }

    // ---------- helpers ----------

    private static List<String> checkHeader(Csv.Table table, List<String> required) {
        List<String> problems = new ArrayList<>();
        for (String col : required) {
            if (!table.header().contains(col)) problems.add("Missing required column \"" + col + "\".");
        }
        return problems;
    }

    private static Map<String, Integer> index(Csv.Table table) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < table.header().size(); i++) idx.put(table.header().get(i), i);
        return idx;
    }

    private static String cell(List<String> r, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return (i == null || i >= r.size()) ? "" : r.get(i).trim();
    }

    private static LocalDate date(String v, int row, List<String> problems) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            problems.add("Row " + row + ": invalid date \"" + v + "\".");
            return null;
        }
    }

    private static long money(String v, int row, String col, List<String> problems) {
        try {
            Long p = MappingProfile.parseAmountPaise(v);
            return p == null ? 0L : p;
        } catch (NumberFormatException e) {
            problems.add("Row " + row + ": non-numeric " + col + " \"" + v + "\".");
            return 0L;
        }
    }
}
