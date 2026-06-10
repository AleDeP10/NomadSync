package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.orchestrator.SyncOrchestrator;
import io.aledep10.nomadsync.service.GitService;

/**
 * Thrown by {@link GitService} when a Git operation
 * fails due to a local error — merge conflict, empty stash, corrupted repository,
 * or any other non-network failure.
 *
 * <p>{@link SyncOrchestrator} does not retry
 * on this exception type — local errors are not resolved by waiting.</p>
 */
public class GitException extends NomadSyncException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
