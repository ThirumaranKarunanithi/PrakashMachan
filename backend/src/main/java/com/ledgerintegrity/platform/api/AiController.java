package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.ai.AiExplanationService;
import com.ledgerintegrity.platform.ai.AiNote;
import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI drafting endpoints (guide §12). Outputs are DRAFTS: labelled, cached,
 * versioned, attributed — and never turned into a record without a human action.
 */
@RestController
@RequestMapping("/api")
public class AiController {

    public record AiNoteDto(String output, String model, String promptVersion,
                            String createdBy, Instant createdAt, boolean cached) {
        static AiNoteDto from(AiNote n, boolean cached) {
            return new AiNoteDto(n.getOutput(), n.getModel(), n.getPromptVersion(),
                    n.getCreatedBy(), n.getCreatedAt(), cached);
        }
    }

    private final AiExplanationService ai;
    private final TenantGuard guard;
    private final CurrentUser currentUser;
    private final ExceptionCaseRepository exceptions;

    public AiController(AiExplanationService ai, TenantGuard guard, CurrentUser currentUser,
                        ExceptionCaseRepository exceptions) {
        this.ai = ai;
        this.guard = guard;
        this.currentUser = currentUser;
        this.exceptions = exceptions;
    }

    @GetMapping("/ai/status")
    public Map<String, Object> status() {
        currentUser.require();
        return Map.of("enabled", ai.enabled(), "model", ai.model());
    }

    /** Plain-language draft explanation of one exception; cached until refresh=true. */
    @PostMapping("/exceptions/{id}/ai-explain")
    public AiNoteDto explain(@PathVariable UUID id,
                             @RequestParam(defaultValue = "false") boolean refresh) {
        ExceptionCase x = guard.exception(id);
        requireEnabled();
        if (!refresh) {
            var existing = ai.cached("EXCEPTION", x.getId());
            if (existing.isPresent()) return AiNoteDto.from(existing.get(), true);
        }
        return AiNoteDto.from(call(() -> ai.explainException(x, currentUser.actorLabel())), false);
    }

    /** One-paragraph partner summary of a consolidated case; cached until refresh=true. */
    @PostMapping("/cases/{id}/ai-summary")
    public AiNoteDto summarize(@PathVariable UUID id,
                               @RequestParam(defaultValue = "false") boolean refresh) {
        InvestigationCase c = guard.investigationCase(id);
        requireEnabled();
        if (!refresh) {
            var existing = ai.cached("CASE", c.getId());
            if (existing.isPresent()) return AiNoteDto.from(existing.get(), true);
        }
        List<ExceptionCase> members = exceptions
                .findByEngagementIdOrderBySeverityAscExposurePaiseDesc(c.getEngagementId()).stream()
                .filter(x -> c.getId().equals(x.getCaseId())).toList();
        return AiNoteDto.from(call(() -> ai.summarizeCase(c, members, currentUser.actorLabel())), false);
    }

    private void requireEnabled() {
        if (!ai.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI assistance is not configured. Set the ANTHROPIC_API_KEY environment"
                            + " variable on the server (Railway -> Variables) to enable it.");
        }
    }

    /** Provider errors surface as clear statuses; the key never appears in a response. */
    private AiNote call(java.util.function.Supplier<AiNote> supplier) {
        try {
            return supplier.get();
        } catch (com.anthropic.errors.RateLimitException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI provider is rate-limiting requests — try again in a minute.");
        } catch (com.anthropic.errors.AnthropicServiceException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an error (" + e.statusCode() + "). Check the model"
                            + " name and the account's credit balance.");
        } catch (com.anthropic.errors.AnthropicException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the AI provider: " + e.getClass().getSimpleName());
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }
}
