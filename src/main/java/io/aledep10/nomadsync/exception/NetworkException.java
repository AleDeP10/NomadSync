package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.service.GitService;

/**
 * Thrown by {@link GitService} when a Git operation
 * fails due to a network connectivity issue.
 *
 * <p>Recognised network patterns: {@code timeout}, {@code Could not resolve host},
 * {@code Connection refused}, {@code Failed to connect},
 * {@code Network is unreachable}.</p>
 *
 * <p>{@link SyncOrchestrator} applies
 * exponential backoff retry on this exception type.</p>
 */
public class NetworkException extends NomadSyncException {

    public NetworkException(String message) {
        super(message);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
