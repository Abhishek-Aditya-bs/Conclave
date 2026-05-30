package io.conclave.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the audit API.
 *
 * <pre>
 *   GET  /api/v1/decisions                 — list (paginated)
 *   GET  /api/v1/decisions/{decisionId}    — detail
 *   POST /api/v1/decisions/{decisionId}/replay  — re-run the judge on stored evidence
 * </pre>
 *
 * <p>Query params on the list endpoint map onto {@link DecisionFilter}:
 * {@code domain}, {@code verdict_label}, {@code baseline_entity_id},
 * {@code min_score}, {@code max_score}, {@code since}, {@code until},
 * {@code judge_provider}, {@code limit}, {@code offset}. Times use ISO-8601
 * (UTC); scores use [0, 1] doubles.
 */
@RestController
@RequestMapping("/api/v1/decisions")
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public DecisionPage list(
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "verdict_label", required = false) String verdictLabel,
            @RequestParam(name = "baseline_entity_id", required = false) String baselineEntityId,
            @RequestParam(name = "min_score", required = false) Double minScore,
            @RequestParam(name = "max_score", required = false) Double maxScore,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "until", required = false) String until,
            @RequestParam(name = "judge_provider", required = false) String judgeProvider,
            @RequestParam(name = "limit", required = false,
                    defaultValue = "" + DecisionFilter.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "offset", required = false,
                    defaultValue = "" + DecisionFilter.DEFAULT_OFFSET) int offset) {
        DecisionFilter filter = DecisionFilter.builder()
                .domain(domain)
                .verdictLabel(verdictLabel)
                .baselineEntityId(baselineEntityId)
                .minScore(minScore)
                .maxScore(maxScore)
                .since(parseInstantOrNull("since", since))
                .until(parseInstantOrNull("until", until))
                .judgeProvider(judgeProvider)
                .limit(limit)
                .offset(offset)
                .build();
        return auditService.list(filter);
    }

    @GetMapping("/{decisionId}")
    public DecisionDetail detail(@PathVariable("decisionId") UUID decisionId) {
        return auditService.detail(decisionId);
    }

    @PostMapping("/{decisionId}/replay")
    public DecisionDetail replay(@PathVariable("decisionId") UUID decisionId) {
        return auditService.replay(decisionId);
    }

    // -------------------- error handlers --------------------

    @ExceptionHandler(DecisionNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(DecisionNotFoundException exc) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorBody("decision_not_found", exc.getMessage()));
    }

    /**
     * {@link DecisionFilter}'s compact constructor throws this on bad
     * scalar inputs ({@code limit}, {@code minScore}, etc.) — surface
     * as a 400 with a tidy body so the dashboard can show the user
     * what they sent wrong.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> badRequest(IllegalArgumentException exc) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("invalid_argument", exc.getMessage()));
    }

    // -------------------- helpers --------------------

    /**
     * Lenient ISO-8601 parser: {@code null}/blank → {@code null}, malformed
     * → 400 with a useful message. We allow either {@code 2026-05-26T00:00:00Z}
     * or epoch-millis to make the dashboard's URL composition easy.
     */
    private static Instant parseInstantOrNull(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // Try epoch millis first (cheap), then ISO-8601 (canonical).
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignore) {
            // fall through
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exc) {
            throw new IllegalArgumentException(
                    "'" + fieldName + "' must be epoch millis or ISO-8601 (e.g. "
                            + "'2026-05-26T00:00:00Z'); got: " + value);
        }
    }

    /** Stable shape for error responses; the dashboard reads {@code code}. */
    public record ErrorBody(String code, String message) {
    }
}
