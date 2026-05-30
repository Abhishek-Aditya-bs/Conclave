package io.conclave.baseline.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Unit tests for the behavioral textualizer. */
class BaselineTextTest {

    private static Function<String, Object> fields(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m::get;
    }

    @Test
    void fraudTextCapturesBehavior() {
        String t = BaselineText.of("fraud", fields(
                "amountMinor", 1500L, "currency", "USD", "merchantCategoryCode", 5411,
                "billingCountry", "US", "channel", "WEB", "cardPresent", false));
        assertThat(t)
                .contains("payment")
                .contains("amount=small")
                .contains("currency=USD")
                .contains("mcc=5411")
                .contains("billing=US")
                .contains("channel=WEB")
                .contains("card_not_present");
    }

    @Test
    void fraudHighTicketShiftsBucket() {
        // An ATO/bust-out style high-ticket charge lands in a different bucket → text shifts.
        assertThat(BaselineText.fraud(fields("amountMinor", 80_000L))).contains("amount=xlarge");
    }

    @Test
    void amountBuckets() {
        assertThat(BaselineText.amountBucket(500)).isEqualTo("small");
        assertThat(BaselineText.amountBucket(5_000)).isEqualTo("medium");
        assertThat(BaselineText.amountBucket(30_000)).isEqualTo("large");
        assertThat(BaselineText.amountBucket(200_000)).isEqualTo("xlarge");
    }

    @Test
    void securityTextCapturesBehavior() {
        String t = BaselineText.of("security", fields(
                "authMethod", "SSO", "result", "SUCCESS", "hostId", "workstation-1",
                "isPrivileged", false, "userAgent", "Mozilla/5.0"));
        assertThat(t)
                .contains("auth")
                .contains("method=SSO")
                .contains("result=SUCCESS")
                .contains("host=workstation-1")
                .contains("standard")
                .contains("no_resource")
                .contains("agent=Mozilla/5.0");
    }

    @Test
    void securityPrivilegedResourceAccessShiftsText() {
        String t = BaselineText.security(fields(
                "authMethod", "API_KEY", "result", "SUCCESS", "hostId", "prod-db-1",
                "isPrivileged", true, "targetResource", "/billing/dump.sql", "userAgent", "boto3/1.34"));
        assertThat(t).contains("privileged").contains("resource=/billing/dump.sql").contains("agent=boto3/1.34");
    }

    @Test
    void toleratesMissingFields() {
        // All fields absent → still a stable, non-empty string (no NPE).
        assertThat(BaselineText.of("fraud", fields())).contains("payment").contains("currency=unknown");
        assertThat(BaselineText.of("security", fields())).contains("auth").contains("method=unknown");
    }
}
