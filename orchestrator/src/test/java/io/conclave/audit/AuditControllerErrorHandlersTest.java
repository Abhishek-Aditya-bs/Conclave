package io.conclave.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Direct unit test of the error-handler methods. The full HTTP wiring
 * is covered by {@code AuditApiIT}; this test pins the response shape
 * the dashboard depends on.
 */
class AuditControllerErrorHandlersTest {

    private final AuditController controller = new AuditController(null);

    @Test
    void not_found_handler_returns_404_with_stable_code() {
        UUID id = UUID.randomUUID();
        ResponseEntity<AuditController.ErrorBody> response =
                controller.notFound(new DecisionNotFoundException(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("decision_not_found");
        assertThat(response.getBody().message()).contains(id.toString());
    }

    @Test
    void illegal_argument_handler_returns_400_with_stable_code() {
        ResponseEntity<AuditController.ErrorBody> response =
                controller.badRequest(new IllegalArgumentException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("invalid_argument");
        assertThat(response.getBody().message()).isEqualTo("bad");
    }
}
