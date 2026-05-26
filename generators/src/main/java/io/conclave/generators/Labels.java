package io.conclave.generators;

/**
 * Ground-truth labels emitted on the {@code events.{domain}.labels} side topic.
 *
 * <p>Labels are published in lockstep with each raw event but on a SEPARATE topic
 * so the M6 orchestrator (which subscribes to {@code events.{domain}.enriched})
 * never sees them. The eval pipeline joins decisions ↔ labels on {@code eventId}
 * to compute AUC + precision@FPR=1%.
 *
 * <p>Naming is domain-agnostic so a future eval job can downcast per-domain.
 */
public enum Labels {
    /** Indistinguishable from organic traffic. The bulk of generated events. */
    CLEAN,

    // ----- Fraud -----
    /** Card-testing ring: one device, many freshly stolen cards, low-value test charges. */
    CARD_TESTING_RING,
    /** Account takeover: legitimate cardholder's pattern suddenly broken (geo, MCC). */
    FRAUD_ATO,
    /** Bust-out: gradual ramp from clean small txns to large abnormal charges. */
    BUST_OUT,

    // ----- Security -----
    /** Lateral movement: one principal authenticating across many hosts in a short window. */
    LATERAL_MOVEMENT,
    /** Data exfiltration: authenticated access to many sensitive resources in sequence. */
    EXFILTRATION,
    /** Account takeover: principal pattern broken (geo, time-of-day, auth method). */
    SECURITY_ATO
}
