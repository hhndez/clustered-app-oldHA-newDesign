package com.solacesystems.poc.model;

/**
 * Represents the application cluster member state with respect to the input stream sequence.
 */
public enum SeqState {
    /**
     * The instance is in initial state, before connecting, binding, or reading
     */
    INIT,
    /**
     * The instance is connected to the cluster but has not yet bound to queues
     */
    CONNECTED,
    /**
     * The instance is connected to the cluster and bound to application and last-value queues
     */
    BOUND,
    /**
     * The instance is connected to the cluster and recovering from the last-value queue
     */
    RECOVERING,
    /**
     * The instance is connected to the cluster and has last-known state from the last-value queue,
     * now is reading the application queue to catch up to that state.
     */
    RECOVERING_FROM_FLOW,
    /**
     * The instance is connected to the cluster and it's application state is caught up with the input flow.
     */
    UPTODATE
}
