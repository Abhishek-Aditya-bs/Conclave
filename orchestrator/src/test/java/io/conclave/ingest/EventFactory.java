package io.conclave.ingest;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test-only factory for building well-formed Avro event records.
 *
 * <p>Used by both unit tests (Avro round-trip) and integration tests (Kafka producer).
 * Every helper returns events that satisfy every non-null field constraint in the schema,
 * so failures during a test indicate a real bug — not a fixture issue.
 */
final class EventFactory {

    private EventFactory() { /* static-only */ }

    static PaymentEvent randomPaymentEvent() {
        Channel[] channels = Channel.values();
        return PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setCardholderId("cust_" + Math.abs(ThreadLocalRandom.current().nextLong()))
                .setCardToken("tok_" + UUID.randomUUID())
                .setAmountMinor(ThreadLocalRandom.current().nextLong(50, 500_000))
                .setCurrency("USD")
                .setMerchantId("merch_" + ThreadLocalRandom.current().nextInt(1, 1000))
                .setMerchantCategoryCode(5411)
                .setBin("424242")
                .setDeviceFingerprint("dev_" + UUID.randomUUID())
                .setIpAddress(randomIpV4())
                .setBillingCountry("US")
                .setShippingCountry("US")
                .setCardPresent(false)
                .setChannel(channels[ThreadLocalRandom.current().nextInt(channels.length)])
                .build();
    }

    static List<PaymentEvent> paymentEvents(int n) {
        List<PaymentEvent> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(randomPaymentEvent());
        }
        return out;
    }

    static AuthEvent randomAuthEvent() {
        return AuthEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setPrincipalId("user_" + ThreadLocalRandom.current().nextInt(1, 10_000))
                .setHostId("host_" + ThreadLocalRandom.current().nextInt(1, 500))
                .setSourceIp(randomIpV4())
                .setAuthMethod(AuthMethod.PASSWORD)
                .setResult(AuthResult.SUCCESS)
                .setTargetResource("/api/v1/widgets")
                .setUserAgent("Mozilla/5.0 conclave-test")
                .setSessionId(UUID.randomUUID().toString())
                .setIsPrivileged(false)
                .build();
    }

    static List<AuthEvent> authEvents(int n) {
        List<AuthEvent> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(randomAuthEvent());
        }
        return out;
    }

    private static String randomIpV4() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return r.nextInt(1, 255) + "." + r.nextInt(0, 255) + "." + r.nextInt(0, 255) + "." + r.nextInt(1, 255);
    }
}
