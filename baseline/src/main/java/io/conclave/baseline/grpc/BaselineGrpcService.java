package io.conclave.baseline.grpc;

import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.proto.BaselineServiceGrpc;
import io.conclave.baseline.proto.GetBaselineRequest;
import io.conclave.baseline.proto.GetBaselineResponse;
import io.conclave.baseline.proto.NotFound;
import io.conclave.baseline.proto.UpdateBaselineRequest;
import io.conclave.baseline.proto.UpdateBaselineResponse;
import io.conclave.baseline.service.BaselineService;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC surface for the baseline service. Translates between proto messages and the
 * internal {@link Baseline} record + delegates to {@link BaselineService}.
 *
 * <p>The proto contract is in {@code baseline.proto}; generated stubs land in
 * {@code io.conclave.baseline.grpc}. We extend the generated
 * {@code BaselineServiceGrpc.BaselineServiceImplBase} so adding RPCs only requires
 * updating the proto + this file.
 */
@GrpcService
public class BaselineGrpcService
        extends BaselineServiceGrpc.BaselineServiceImplBase {

    private final BaselineService service;

    public BaselineGrpcService(BaselineService service) {
        this.service = service;
    }

    @Override
    public void getBaseline(GetBaselineRequest request,
                            StreamObserver<GetBaselineResponse> observer) {
        Optional<Baseline> found = service.get(request.getDomain(), request.getEntityId());
        GetBaselineResponse response = found
                .map(b -> GetBaselineResponse.newBuilder()
                        .setBaseline(toProto(b))
                        .build())
                .orElseGet(() -> GetBaselineResponse.newBuilder()
                        .setNotFound(NotFound.newBuilder()
                                .setMessage("no baseline for entity " + request.getEntityId()
                                          + " in domain " + request.getDomain())
                                .build())
                        .build());
        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void updateBaseline(UpdateBaselineRequest request,
                               StreamObserver<UpdateBaselineResponse> observer) {
        Baseline updated = service.update(
                request.getDomain(),
                request.getEntityId(),
                request.getEventText());
        observer.onNext(UpdateBaselineResponse.newBuilder()
                .setBaseline(toProto(updated))
                .build());
        observer.onCompleted();
    }

    private static io.conclave.baseline.proto.Baseline toProto(Baseline b) {
        io.conclave.baseline.proto.Baseline.Builder builder = io.conclave.baseline.proto.Baseline.newBuilder()
                .setEntityId(b.entityId())
                .setDomain(b.domain())
                .setEventCount(b.eventCount())
                .setLastUpdatedEpochMs(b.lastUpdated().toEpochMilli());
        for (float f : b.embedding()) {
            builder.addEmbedding(f);
        }
        return builder.build();
    }
}
