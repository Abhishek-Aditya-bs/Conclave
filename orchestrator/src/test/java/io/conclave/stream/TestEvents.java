package io.conclave.stream;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-only event factories for the M2 stream-package tests. The M1 EventFactory is
 * package-private to {@code io.conclave.ingest}; this helper duplicates the minimum
 * needed for stream tests rather than relaxing M1's visibility.
 */
final class TestEvents {

    private TestEvents() { /* static-only */ }

    static PaymentEvent paymentEventForCardholder(String cardholderId) {
        return PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setCardholderId(cardholderId)
                .setCardToken("tok_" + UUID.randomUUID())
                .setAmountMinor(2500L)
                .setCurrency("USD")
                .setMerchantId("merch_acme")
                .setMerchantCategoryCode(5411)
                .setBin("424242")
                .setDeviceFingerprint("dev_" + cardholderId)
                .setIpAddress("10.0.0.1")
                .setBillingCountry("US")
                .setShippingCountry("US")
                .setCardPresent(false)
                .setChannel(Channel.WEB)
                .build();
    }

    static AuthEvent authEventFor(String principalId, AuthResult result) {
        return AuthEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setPrincipalId(principalId)
                .setHostId("host_" + principalId.hashCode() % 10)
                .setSourceIp("10.0.0.10")
                .setAuthMethod(AuthMethod.PASSWORD)
                .setResult(result)
                .setTargetResource("/api/v1/data")
                .setUserAgent("test-agent")
                .setSessionId(UUID.randomUUID().toString())
                .setIsPrivileged(false)
                .build();
    }
}
