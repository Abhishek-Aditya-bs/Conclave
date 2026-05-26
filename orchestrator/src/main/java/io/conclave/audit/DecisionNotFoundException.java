package io.conclave.audit;

import java.util.UUID;

/**
 * Thrown when an audit request references a decision id that has no row
 * in {@code decisions}. The REST controller maps this onto a 404.
 */
public class DecisionNotFoundException extends RuntimeException {

    private final UUID decisionId;

    public DecisionNotFoundException(UUID decisionId) {
        super("No decision found with id " + decisionId);
        this.decisionId = decisionId;
    }

    public UUID decisionId() {
        return decisionId;
    }
}
