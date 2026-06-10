package io.aledep10.nomadsync.gitignore.exception;

import io.aledep10.nomadsync.exception.NomadSyncException;
import io.aledep10.nomadsync.service.GitignoreService;

/**
 * Thrown by {@link GitignoreService} when an exception occurs while performing gitignore patterns
 * CRUD operations.
 */
public class GitignoreException extends NomadSyncException {

    public GitignoreException(String message) {
        super(message);
    }

    public GitignoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
