package io.conclave.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.orchestrator.client.DeliberationClient;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Business logic for the audit endpoints.
 *
 * <ul>
 *   <li>{@link #list(DecisionFilter)} — paginated browse.</li>
 *   <li>{@link #detail(UUID)} — one row, 404 if missing.</li>
 *   <li>{@link #replay(UUID)} — re-run the deliberation on the stored
 *       evidence and return a fresh decision <em>without persisting</em>
 *       (the original audit row stays untouched).</li>
 * </ul>
 *
 * <p>Replay parses {@code graphEntityIds} out of the stored
 * {@code enrichedEventJson} so the orchestration scalars match what the
 * original request carried. The eventId / domain / baselineEntityId
 * scalars come straight from the row.
 */
@Service
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final DecisionAuditRepository repository;
    private final DeliberationClient deliberationClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(
            DecisionAuditRepository repository,
            DeliberationClient deliberationClient) {
        this.repository = repository;
        this.deliberationClient = deliberationClient;
    }

    public DecisionPage list(DecisionFilter filter) {
        long total = repository.count(filter);
        // Skip the items query when there's nothing to return — saves a
        // round trip for pages past the end.
        List<DecisionSummary> items = total == 0
                ? List.of()
                : repository.findAll(filter);
        return new DecisionPage(items, total, filter.limit(), filter.offset());
    }

    public DecisionDetail detail(UUID decisionId) {
        DecisionRecord record = repository.findById(decisionId)
                .orElseThrow(() -> new DecisionNotFoundException(decisionId));
        return DecisionDetail.of(record);
    }

    /**
     * Replay: rebuild the M5 request from the stored row and call M5
     * again. We do NOT persist the new decision — replay is for
     * "what would M5 say now / with a different backend" inspection,
     * not for backfilling audit history.
     */
    public DecisionDetail replay(UUID decisionId) {
        DecisionRecord original = repository.findById(decisionId)
                .orElseThrow(() -> new DecisionNotFoundException(decisionId));

        List<String> graphEntityIds = extractGraphEntityIds(original.enrichedEventJson());
        DeliberationRequest request = DeliberationRequest.newBuilder()
                .setEventId(original.eventId())
                .setDomain(original.domain())
                .setBaselineEntityId(original.baselineEntityId())
                .addAllGraphEntityIds(graphEntityIds)
                .setEnrichedEventJson(original.enrichedEventJson())
                .build();

        LOG.info(
                "Replaying decision {} (event={}, domain={})",
                decisionId, original.eventId(), original.domain());
        DecisionRecord fresh = deliberationClient.deliberate(request);
        return DecisionDetail.of(fresh);
    }

    /**
     * Pull {@code graphEntityIds} out of the stored enriched-event JSON.
     * Field name matches the M2 enriched-schema (see
     * {@code configs/{domain}/enriched-schema.avsc}). Defaults to empty
     * list if the field is missing or unparseable — replay still works,
     * just without graph context.
     */
    private List<String> extractGraphEntityIds(String enrichedEventJson) {
        if (enrichedEventJson == null || enrichedEventJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(enrichedEventJson);
            JsonNode ids = root.get("graphEntityIds");
            if (ids == null || !ids.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(ids.size());
            for (JsonNode element : ids) {
                if (element.isTextual()) {
                    out.add(element.asText());
                }
            }
            return out;
        } catch (Exception parseFailure) {
            LOG.warn(
                    "Could not parse graphEntityIds from enriched_event_json during replay; "
                            + "proceeding with empty list: {}", parseFailure.getMessage());
            return List.of();
        }
    }
}
