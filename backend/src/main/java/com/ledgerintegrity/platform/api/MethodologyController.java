package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfig;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** RSK-003: firm methodology settings — versioned severity weights for review priority. */
@RestController
@RequestMapping("/api/methodology")
public class MethodologyController {

    private final RiskWeightConfigRepository configs;
    private final com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplateRepository templates;
    private final CurrentUser currentUser;

    public MethodologyController(RiskWeightConfigRepository configs,
                                 com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplateRepository templates,
                                 CurrentUser currentUser) {
        this.configs = configs;
        this.templates = templates;
        this.currentUser = currentUser;
    }

    public record TemplateDto(String headerTitle, String footerNote, boolean includeGst,
                              boolean includeBank, boolean includeAuditTrail,
                              int version, String updatedBy) {}

    public record TemplateUpdate(String headerTitle, String footerNote, Boolean includeGst,
                                 Boolean includeBank, Boolean includeAuditTrail) {}

    /** AWP-001: the firm workpaper template. */
    @GetMapping("/workpaper-template")
    public TemplateDto getTemplate() {
        return templates.findTopByFirmIdOrderByVersionDesc(currentUser.firmId())
                .map(t -> new TemplateDto(t.getHeaderTitle(), t.getFooterNote(), t.isIncludeGst(),
                        t.isIncludeBank(), t.isIncludeAuditTrail(), t.getVersion(), t.getUpdatedBy()))
                .orElse(new TemplateDto("Engagement Workpaper", null, true, true, true, 0, "defaults"));
    }

    @PutMapping("/workpaper-template")
    public TemplateDto updateTemplate(@RequestBody TemplateUpdate req) {
        if (req.headerTitle() == null || req.headerTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "headerTitle is required.");
        }
        UUID firmId = currentUser.firmId();
        int next = templates.findTopByFirmIdOrderByVersionDesc(firmId).map(t -> t.getVersion() + 1).orElse(1);
        var t = new com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplate(UUID.randomUUID(), firmId, next,
                req.headerTitle().trim(), req.footerNote(),
                req.includeGst() == null || req.includeGst(),
                req.includeBank() == null || req.includeBank(),
                req.includeAuditTrail() == null || req.includeAuditTrail(),
                currentUser.actorLabel(), Instant.now());
        templates.save(t);
        return new TemplateDto(t.getHeaderTitle(), t.getFooterNote(), t.isIncludeGst(),
                t.isIncludeBank(), t.isIncludeAuditTrail(), t.getVersion(), t.getUpdatedBy());
    }

    public record WeightsDto(int highWeight, int mediumWeight, int lowWeight,
                             int reconciliationCap, int deterministicCap, int behaviourCap,
                             int statisticalCap, int relationshipCap, int evidenceCap,
                             int version, String updatedBy, Instant updatedAt) {}

    public record UpdateRequest(Integer highWeight, Integer mediumWeight, Integer lowWeight,
                                Integer reconciliationCap, Integer deterministicCap, Integer behaviourCap,
                                Integer statisticalCap, Integer relationshipCap, Integer evidenceCap) {}

    @GetMapping("/risk-weights")
    public WeightsDto get() {
        return configs.findTopByFirmIdOrderByVersionDesc(currentUser.firmId())
                .map(MethodologyController::dto)
                .orElse(new WeightsDto(RiskWeightConfig.DEFAULT_HIGH, RiskWeightConfig.DEFAULT_MEDIUM,
                        RiskWeightConfig.DEFAULT_LOW,
                        RiskWeightConfig.DEFAULT_RECONCILIATION_CAP, RiskWeightConfig.DEFAULT_DETERMINISTIC_CAP,
                        RiskWeightConfig.DEFAULT_BEHAVIOUR_CAP, RiskWeightConfig.DEFAULT_STATISTICAL_CAP,
                        RiskWeightConfig.DEFAULT_RELATIONSHIP_CAP, RiskWeightConfig.DEFAULT_EVIDENCE_CAP,
                        0, "defaults (guide 9.1 illustrative)", null));
    }

    /** Append-only: every change is a new version; past configurations remain reviewable. */
    @PutMapping("/risk-weights")
    public WeightsDto update(@RequestBody UpdateRequest req) {
        if (req.highWeight() == null || req.mediumWeight() == null || req.lowWeight() == null
                || req.highWeight() < req.mediumWeight() || req.mediumWeight() < req.lowWeight()
                || req.lowWeight() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Weights must satisfy high >= medium >= low >= 0.");
        }
        UUID firmId = currentUser.firmId();
        int next = configs.findTopByFirmIdOrderByVersionDesc(firmId)
                .map(c -> c.getVersion() + 1).orElse(1);
        // Family caps (guide 9.1): optional; omitted caps keep the previous version's
        // values (or the defaults). Caps must be non-negative and sum to at most 100
        // so the total Review Priority Score stays on the 0-100 scale.
        var prev = configs.findTopByFirmIdOrderByVersionDesc(firmId).orElse(null);
        int rec = cap(req.reconciliationCap(), prev == null ? RiskWeightConfig.DEFAULT_RECONCILIATION_CAP : prev.getReconciliationCap());
        int det = cap(req.deterministicCap(), prev == null ? RiskWeightConfig.DEFAULT_DETERMINISTIC_CAP : prev.getDeterministicCap());
        int beh = cap(req.behaviourCap(), prev == null ? RiskWeightConfig.DEFAULT_BEHAVIOUR_CAP : prev.getBehaviourCap());
        int sta = cap(req.statisticalCap(), prev == null ? RiskWeightConfig.DEFAULT_STATISTICAL_CAP : prev.getStatisticalCap());
        int rel = cap(req.relationshipCap(), prev == null ? RiskWeightConfig.DEFAULT_RELATIONSHIP_CAP : prev.getRelationshipCap());
        int evi = cap(req.evidenceCap(), prev == null ? RiskWeightConfig.DEFAULT_EVIDENCE_CAP : prev.getEvidenceCap());
        if (rec + det + beh + sta + rel + evi > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Family caps must sum to at most 100 (got " + (rec + det + beh + sta + rel + evi) + ").");
        }
        RiskWeightConfig c = new RiskWeightConfig(UUID.randomUUID(), firmId, next,
                req.highWeight(), req.mediumWeight(), req.lowWeight(),
                currentUser.actorLabel(), Instant.now());
        c.setFamilyCaps(rec, det, beh, sta, rel, evi);
        configs.save(c);
        return dto(c);
    }

    private static int cap(Integer requested, int fallback) {
        if (requested == null) return fallback;
        if (requested < 0 || requested > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each family cap must be between 0 and 100.");
        }
        return requested;
    }

    private static WeightsDto dto(RiskWeightConfig c) {
        return new WeightsDto(c.getHighWeight(), c.getMediumWeight(), c.getLowWeight(),
                c.getReconciliationCap(), c.getDeterministicCap(), c.getBehaviourCap(),
                c.getStatisticalCap(), c.getRelationshipCap(), c.getEvidenceCap(),
                c.getVersion(), c.getUpdatedBy(), c.getUpdatedAt());
    }
}
