package io.conclave.graph.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import io.conclave.graph.proto.ExecuteTemplateRequest;
import io.conclave.graph.proto.ExecuteTemplateResponse;
import io.conclave.graph.proto.GraphReasonerServiceGrpc;
import io.conclave.graph.proto.ListTemplatesRequest;
import io.conclave.graph.proto.ListTemplatesResponse;
import io.conclave.graph.proto.TemplateInfo;
import io.conclave.graph.proto.TemplateNotFound;
import io.conclave.graph.service.GraphReasonerService;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC surface for the graph reasoner. Translates between proto messages and the
 * internal {@link GraphFinding} record, then delegates to {@link GraphReasonerService}.
 *
 * <p>Template-specific attributes are serialized to JSON for the wire because each
 * template emits a different shape; consumers parse with whichever JSON library they
 * prefer.
 */
@GrpcService
public class GraphGrpcService
        extends GraphReasonerServiceGrpc.GraphReasonerServiceImplBase {

    private final GraphReasonerService service;
    private final ObjectMapper objectMapper;

    public GraphGrpcService(GraphReasonerService service) {
        this.service = service;
        // ObjectMapper is stateless for writing; one instance per service is fine.
        // Spring Boot 4's JSON auto-config doesn't always register an ObjectMapper bean
        // when only spring-boot-starter-web is on the classpath without the Jackson
        // starter — instantiating directly keeps us independent of that.
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void listTemplates(ListTemplatesRequest request,
                              StreamObserver<ListTemplatesResponse> observer) {
        ListTemplatesResponse.Builder responseBuilder = ListTemplatesResponse.newBuilder();
        for (GraphTemplate template : service.listTemplates(request.getDomain())) {
            responseBuilder.addTemplates(TemplateInfo.newBuilder()
                    .setName(template.name())
                    .setDomain(template.domain())
                    .setDescription(template.description())
                    .build());
        }
        observer.onNext(responseBuilder.build());
        observer.onCompleted();
    }

    @Override
    public void executeTemplate(ExecuteTemplateRequest request,
                                StreamObserver<ExecuteTemplateResponse> observer) {
        Optional<GraphFinding> finding = service.execute(
                request.getTemplateName(),
                request.getParamsMap());

        ExecuteTemplateResponse response = finding
                .map(this::toProto)
                .orElseGet(() -> ExecuteTemplateResponse.newBuilder()
                        .setError(TemplateNotFound.newBuilder()
                                .setTemplateName(request.getTemplateName())
                                .setMessage("No template registered with name '"
                                          + request.getTemplateName() + "'")
                                .build())
                        .build());

        observer.onNext(response);
        observer.onCompleted();
    }

    private ExecuteTemplateResponse toProto(GraphFinding f) {
        String attributesJson;
        try {
            attributesJson = objectMapper.writeValueAsString(f.attributes());
        } catch (JsonProcessingException e) {
            // Templates only emit JSON-serializable values (primitives, strings, lists,
            // maps); a failure here is a code bug, not a runtime expectation.
            throw new IllegalStateException(
                    "Failed to serialize template attributes: " + f.templateName(), e);
        }
        return ExecuteTemplateResponse.newBuilder()
                .setFinding(io.conclave.graph.proto.GraphFinding.newBuilder()
                        .setTemplateName(f.templateName())
                        .setRootEntityId(f.rootEntityId())
                        .setDomain(f.domain())
                        .setAttributesJson(attributesJson)
                        .setRiskSignal(f.riskSignal())
                        .setQueryLatencyMs(f.queryLatencyMs())
                        .build())
                .build();
    }
}
