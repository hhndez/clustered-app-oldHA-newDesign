package com.solacesystems.poc.model;

/**
 * Represents the application cluster member HA state
 */
public enum HAState {
    /**
     * The instance is disconnected from the cluster
     */
    DISCONNECTED,
    /**
     * The instance is connected to the cluster, but has not yet determined it's HA State
     */
    CONNECTED,
    /**
     * The instance is connected to the cluster as the Hot Backup
     */
    BACKUP,
    /**
     * The instance is connected to the cluster as the Active member responsible for outputting application state
     */
    ACTIVE
}
