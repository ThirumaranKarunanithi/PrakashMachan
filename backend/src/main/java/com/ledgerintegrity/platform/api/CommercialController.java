package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.persist.AppUser;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.engagement.Module;
import com.ledgerintegrity.platform.engagement.PriceConfig;
import com.ledgerintegrity.platform.engagement.PriceConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Screen 2's commercial layer: customers (the firm's clients across their
 * engagement years), the firm's per-module price list, and the billing summary.
 * Prices are firm-configured placeholders until real commercial terms exist —
 * the platform computes fees, it does not process payments.
 */
@RestController
@RequestMapping("/api")
public class CommercialController {

    private final EngagementRepository engagements;
    private final PriceConfigRepository prices;
    private final CurrentUser currentUser;

    public CommercialController(EngagementRepository engagements, PriceConfigRepository prices,
                                CurrentUser currentUser) {
        this.engagements = engagements;
        this.prices = prices;
        this.currentUser = currentUser;
    }

    private PriceConfig priceList(UUID firmId) {
        return prices.findTopByFirmIdOrderByVersionDesc(firmId).orElse(PriceConfig.defaults(firmId));
    }

    private long feeFor(Engagement e, PriceConfig p) {
        long fee = p.getCorePaise();
        for (String m : e.getSubscribedModules()) {
            try {
                fee += p.priceFor(Module.valueOf(m));
            } catch (IllegalArgumentException ignored) { /* unknown module carries no price */ }
        }
        return fee;
    }

    // ---------- pricing & plans ----------

    public record PricingDto(long corePaise, long gstPaise, long bankPaise, long vendorPaise,
                             long auditTrailPaise, int version, String updatedBy, Instant updatedAt) {}

    public record PricingUpdate(Long corePaise, Long gstPaise, Long bankPaise,
                                Long vendorPaise, Long auditTrailPaise) {}

    @GetMapping("/pricing")
    public PricingDto pricing() {
        PriceConfig p = priceList(currentUser.firmId());
        return new PricingDto(p.getCorePaise(), p.getGstPaise(), p.getBankPaise(),
                p.getVendorPaise(), p.getAuditTrailPaise(), p.getVersion(), p.getUpdatedBy(), p.getUpdatedAt());
    }

    /** Versioned append-only; ADMIN/PARTNER only. Omitted values keep the previous price. */
    @PutMapping("/pricing")
    public PricingDto updatePricing(@RequestBody PricingUpdate req) {
        AppUser user = currentUser.require();
        if (user.getRole() != AppUser.Role.ADMIN && user.getRole() != AppUser.Role.PARTNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an ADMIN or PARTNER can change the price list.");
        }
        PriceConfig prev = priceList(user.getFirmId());
        PriceConfig next = new PriceConfig(UUID.randomUUID(), user.getFirmId(), prev.getVersion() + 1,
                price(req.corePaise(), prev.getCorePaise()),
                price(req.gstPaise(), prev.getGstPaise()),
                price(req.bankPaise(), prev.getBankPaise()),
                price(req.vendorPaise(), prev.getVendorPaise()),
                price(req.auditTrailPaise(), prev.getAuditTrailPaise()),
                currentUser.actorLabel(), Instant.now());
        prices.save(next);
        return new PricingDto(next.getCorePaise(), next.getGstPaise(), next.getBankPaise(),
                next.getVendorPaise(), next.getAuditTrailPaise(), next.getVersion(),
                next.getUpdatedBy(), next.getUpdatedAt());
    }

    private static long price(Long requested, long fallback) {
        if (requested == null) return fallback;
        if (requested < 0 || requested > 100_00_00_000_00L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prices must be between 0 and Rs 100 crore.");
        }
        return requested;
    }

    // ---------- customers (clients across engagement years) ----------

    public record CustomerDto(String name, int engagementYears, String latestFy, String latestEngagementId,
                              List<String> modules, long estimatedFeePaise) {}

    @GetMapping("/customers")
    public List<CustomerDto> customers() {
        UUID firmId = currentUser.firmId();
        PriceConfig p = priceList(firmId);
        Map<String, List<Engagement>> byClient = new TreeMap<>();
        for (Engagement e : engagements.findByFirmIdOrderByCreatedAtDesc(firmId)) {
            byClient.computeIfAbsent(e.getClientName(), k -> new ArrayList<>()).add(e);
        }
        List<CustomerDto> out = new ArrayList<>();
        byClient.forEach((name, list) -> {
            Engagement latest = list.stream()
                    .max(java.util.Comparator.comparing(Engagement::getFyEnd)).orElseThrow();
            out.add(new CustomerDto(name, list.size(),
                    latest.getFyStart() + " to " + latest.getFyEnd(), latest.getId().toString(),
                    List.copyOf(latest.getSubscribedModules()), feeFor(latest, p)));
        });
        return out;
    }

    // ---------- billing summary ----------

    public record BillingLine(String client, String financialYear, List<String> modules,
                              long corePaise, long addOnsPaise, long feePaise) {}

    public record BillingSummary(List<BillingLine> lines, long totalPaise, int pricingVersion) {}

    @GetMapping("/billing")
    public BillingSummary billing() {
        UUID firmId = currentUser.firmId();
        PriceConfig p = priceList(firmId);
        List<BillingLine> lines = new ArrayList<>();
        long total = 0;
        for (Engagement e : engagements.findByFirmIdOrderByCreatedAtDesc(firmId)) {
            long fee = feeFor(e, p);
            lines.add(new BillingLine(e.getClientName(), e.getFyStart() + " to " + e.getFyEnd(),
                    List.copyOf(e.getSubscribedModules()), p.getCorePaise(), fee - p.getCorePaise(), fee));
            total += fee;
        }
        return new BillingSummary(lines, total, p.getVersion());
    }

    @GetMapping(value = "/billing.csv", produces = "text/csv")
    public ResponseEntity<String> billingCsv() {
        BillingSummary b = billing();
        List<List<String>> rows = new ArrayList<>();
        for (BillingLine l : b.lines()) {
            rows.add(List.of(l.client(), l.financialYear(), String.join(" ", l.modules()),
                    inr(l.corePaise()), inr(l.addOnsPaise()), inr(l.feePaise())));
        }
        rows.add(List.of("TOTAL", "", "", "", "", inr(b.totalPaise())));
        String csv = Csv.serialize(
                List.of("client", "financial_year", "modules", "core_inr", "add_ons_inr", "fee_inr"), rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=billing-summary.csv")
                .body(csv);
    }

    private static String inr(long paise) {
        return String.format("%.2f", paise / 100.0);
    }

    // uniform JSON errors, matching the other controllers
    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", ex.getReason() == null ? ex.getMessage() : ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
}
