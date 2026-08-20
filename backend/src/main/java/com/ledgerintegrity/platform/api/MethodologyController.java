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
                             int version, String updatedBy, Instant updatedAt) {}

    public record UpdateRequest(Integer highWeight, Integer mediumWeight, Integer lowWeight) {}

    @GetMapping("/risk-weights")
    public WeightsDto get() {
        return configs.findTopByFirmIdOrderByVersionDesc(currentUser.firmId())
                .map(c -> new WeightsDto(c.getHighWeight(), c.getMediumWeight(), c.getLowWeight(),
                        c.getVersion(), c.getUpdatedBy(), c.getUpdatedAt()))
                .orElse(new WeightsDto(RiskWeightConfig.DEFAULT_HIGH, RiskWeightConfig.DEFAULT_MEDIUM,
                        RiskWeightConfig.DEFAULT_LOW, 0, "defaults (BRD 17.1 illustrative)", null));
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
        RiskWeightConfig c = new RiskWeightConfig(UUID.randomUUID(), firmId, next,
                req.highWeight(), req.mediumWeight(), req.lowWeight(),
                currentUser.actorLabel(), Instant.now());
        configs.save(c);
        return new WeightsDto(c.getHighWeight(), c.getMediumWeight(), c.getLowWeight(),
                c.getVersion(), c.getUpdatedBy(), c.getUpdatedAt());
    }
}
