package io.conclave.graph.rest;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import io.conclave.graph.service.GraphReasonerService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the graph reasoner. Mirrors the gRPC contract field-for-field.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/graph/templates?domain=fraud} — list templates.</li>
 *   <li>{@code POST /api/v1/graph/templates/{name}/execute} — run a template
 *       with body = JSON map of params.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final GraphReasonerService service;

    public GraphController(GraphReasonerService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<TemplateInfo> listTemplates(@RequestParam(required = false) String domain) {
        return service.listTemplates(domain).stream()
                .map(t -> new TemplateInfo(t.name(), t.domain(), t.description()))
                .toList();
    }

    @PostMapping("/templates/{name}/execute")
    public ResponseEntity<GraphFindingDto> execute(@PathVariable String name,
                                                   @RequestBody Map<String, String> params) {
        return service.execute(name, params)
                .map(f -> ResponseEntity.ok(GraphFindingDto.from(f)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record TemplateInfo(String name, String domain, String description) {}

    public record GraphFindingDto(
            String templateName,
            String rootEntityId,
            String domain,
            Map<String, Object> attributes,
            double riskSignal,
            long queryLatencyMs) {

        public static GraphFindingDto from(GraphFinding f) {
            return new GraphFindingDto(
                    f.templateName(),
                    f.rootEntityId(),
                    f.domain(),
                    f.attributes(),
                    f.riskSignal(),
                    f.queryLatencyMs());
        }
    }
}
