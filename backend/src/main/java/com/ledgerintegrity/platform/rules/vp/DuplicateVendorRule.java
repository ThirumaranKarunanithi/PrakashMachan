package com.ledgerintegrity.platform.rules.vp;

import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.Rule;
import com.ledgerintegrity.platform.vendor.persist.VendorRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VP-01/VP-02: different vendor-master records sharing the same bank account, with a
 * note when the names are near-identical. Shared details can be legitimate (group
 * companies, shared services) — hence a review flag, never a duplicate conclusion.
 */
public class DuplicateVendorRule implements Rule {

    @Override public String id() { return "VP-01"; }
    @Override public String name() { return "Duplicate vendor / shared bank account"; }

    @Override
    public List<Finding> evaluate(Context ctx) {
        Map<String, List<VendorRecord>> byBank = new LinkedHashMap<>();
        for (VendorRecord v : ctx.vendors()) {
            if (v.getBankAccount() == null || v.getBankAccount().isBlank()) continue;
            String key = v.getBankAccount() + "|" + (v.getIfsc() == null ? "" : v.getIfsc());
            byBank.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }

        List<Finding> findings = new ArrayList<>();
        for (List<VendorRecord> group : byBank.values()) {
            if (group.size() < 2) continue;
            boolean namesSimilar = group.stream().map(v -> normalize(v.getName())).distinct().count() == 1;
            String parties = group.stream()
                    .map(v -> v.getVendorId() + " \"" + v.getName() + "\" (created " + v.getCreatedDate()
                            + " by " + v.getCreatedBy() + ")")
                    .collect(Collectors.joining(" and "));
            findings.add(new Finding(id(), name(), Finding.Severity.HIGH,
                    0,
                    "Vendors " + parties + " share bank account " + group.get(0).getBankAccount()
                            + " / " + group.get(0).getIfsc() + "."
                            + (namesSimilar ? " Names are near-identical after normalisation." : ""),
                    group.stream().map(v -> "VENDOR:" + v.getVendorId()).sorted().toList(),
                    group.stream().map(v -> v.getSourceFile() + ":" + v.getSourceRow())
                            .collect(Collectors.joining(" "))));
        }
        return findings;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("\\b(pvt|ltd|llp|co|company|enterprises|traders|services|india)\\b", "")
                .replaceAll("[^a-z]", "")
                // collapse common transliteration variants (shree/shri/sri)
                .replaceAll("shree|shri|sri", "sri");
    }
}
