package com.ledgerintegrity.platform.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.ledgerintegrity.platform.rules.RiskFamily;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The AI layer sits AROUND the integrity core, never inside it (guide §12).
 * It receives structured, already-computed facts and drafts plain language;
 * it cannot change data, formulas, scores or conclusions. Every output is
 * stored with its model and prompt version and labelled a draft.
 */
@Service
public class AiExplanationService {

    static final String PROMPT_VERSION = "ai-explain-0.1.0";

    static final String SYSTEM_PROMPT = """
            You draft review notes for chartered accountants using an audit risk platform.
            You receive FACTS computed by a deterministic engine. Rules that are absolute:
            - Never state or imply that fraud, manipulation or wrongdoing occurred.
            - A signal is a reason to look, not a conclusion; keep wording neutral.
            - Do not invent numbers, names or facts beyond those provided.
            - Plain language an audit associate can act on. No headings, no markdown.
            Structure the note as: (1) what was flagged, in one sentence; (2) why it
            deserves review; (3) common innocent explanations; (4) the specific evidence
            or procedure that would resolve it. At most 160 words.""";

    private final AiNoteRepository notes;
    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public AiExplanationService(AiNoteRepository notes,
                                @Value("${app.ai.api-key:}") String apiKey,
                                @Value("${app.ai.model:claude-opus-5}") String model) {
        this.notes = notes;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "claude-opus-5" : model.trim();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    public Optional<AiNote> cached(String subjectType, UUID subjectId) {
        return notes.findTopBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subjectType, subjectId);
    }

    public AiNote explainException(ExceptionCase x, String actor) {
        String facts = exceptionFacts(x);
        return generate(x.getEngagementId(), "EXCEPTION", x.getId(),
                "Draft a review note for this flagged item.\n\n" + facts, actor);
    }

    public AiNote summarizeCase(InvestigationCase c, List<ExceptionCase> members, String actor) {
        StringBuilder facts = new StringBuilder();
        facts.append("CASE ").append(c.getCaseNo()).append(": ").append(c.getTitle()).append('\n');
        facts.append("Review priority score: ").append(c.getPriorityScore())
                .append("/100, family breakdown: ").append(c.getFamilyScoresJson()).append('\n');
        facts.append("Estimated exposure: Rs ").append(String.format("%,.2f", c.getExposurePaise() / 100.0)).append('\n');
        facts.append("Member signals (").append(members.size()).append("):\n");
        for (ExceptionCase m : members) {
            facts.append("- [").append(m.getRuleId()).append(" ").append(RiskFamily.of(m.getRuleId()))
                    .append(" ").append(m.getSeverity()).append("] ").append(m.getReason()).append('\n');
        }
        return generate(c.getEngagementId(), "CASE", c.getId(),
                "Summarise this consolidated investigation case for a partner in one paragraph, "
                        + "noting which INDEPENDENT method families corroborate.\n\n" + facts, actor);
    }

    static String exceptionFacts(ExceptionCase x) {
        return "Rule: " + x.getRuleId() + " (" + x.getRuleName() + "), family "
                + RiskFamily.of(x.getRuleId()) + ", severity " + x.getSeverity() + "\n"
                + "Exposure: Rs " + String.format("%,.2f", x.getExposurePaise() / 100.0) + "\n"
                + "Vouchers: " + x.getVoucherIds() + "\n"
                + "Source rows: " + x.getSourceRefs() + "\n"
                + "Engine finding: " + x.getReason() + "\n"
                + "Current status: " + x.getStatus();
    }

    private AiNote generate(UUID engagementId, String subjectType, UUID subjectId,
                            String userContent, String actor) {
        if (!enabled()) {
            throw new IllegalStateException("AI assistance is not configured."
                    + " Set the ANTHROPIC_API_KEY environment variable on the server to enable it.");
        }
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L) // short drafts by design: keeps spend predictable
                .system(SYSTEM_PROMPT)
                .addUserMessage(userContent)
                .build();
        String output = client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", (a, b) -> a + b)
                .trim();
        AiNote note = new AiNote(UUID.randomUUID(), engagementId, subjectType, subjectId,
                model, PROMPT_VERSION, output, actor, Instant.now());
        return notes.save(note);
    }
}
