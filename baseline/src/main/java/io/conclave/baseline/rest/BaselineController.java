package io.conclave.baseline.rest;

import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.service.BaselineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the baseline service. Mirrors the gRPC contract field-for-field.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/baselines/{domain}/{entityId}} — current baseline or 404</li>
 *   <li>{@code POST /api/v1/baselines/{domain}/{entityId}} — append event, return updated baseline</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/baselines")
public class BaselineController {

    private final BaselineService service;

    public BaselineController(BaselineService service) {
        this.service = service;
    }

    @GetMapping("/{domain}/{entityId}")
    public ResponseEntity<BaselineDto> get(@PathVariable String domain,
                                           @PathVariable String entityId) {
        return service.get(domain, entityId)
                .map(b -> ResponseEntity.ok(BaselineDto.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{domain}/{entityId}")
    public BaselineDto update(@PathVariable String domain,
                              @PathVariable String entityId,
                              @RequestBody UpdateRequest request) {
        Baseline updated = service.update(domain, entityId, request.eventText());
        return BaselineDto.from(updated);
    }

    /** Request body for the POST endpoint. */
    public record UpdateRequest(String eventText) {}

    /** JSON response shape — flattens {@link Baseline} for wire stability. */
    public record BaselineDto(
            String entityId,
            String domain,
            float[] embedding,
            long eventCount,
            long lastUpdatedEpochMs) {

        public static BaselineDto from(Baseline b) {
            return new BaselineDto(
                    b.entityId(),
                    b.domain(),
                    b.embedding(),
                    b.eventCount(),
                    b.lastUpdated().toEpochMilli());
        }
    }
}
