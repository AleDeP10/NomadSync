package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;

/**
 * Base exception for all NomadSync domain errors.
 *
 * <p>Subclasses distinguish between network-related failures ({@link NetworkException})
 * and local Git errors ({@link GitException}), allowing {@link
 * SyncOrchestrator} to apply different
 * recovery strategies via separate catch blocks.</p>
 */
public class NomadSyncException extends Exception {

    public NomadSyncException(String message) {
        super(message);
    }

    public NomadSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
