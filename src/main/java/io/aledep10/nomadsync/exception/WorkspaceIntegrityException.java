package io.aledep10.nomadsync.exception;

/**
 * Thrown by {@code WorkspaceService} when {@code workspaces.json} is readable
 * but contains logically inconsistent data — for example, two entries sharing
 * the same {@code workspaceName}, or a number of entries marked
 * {@code isDefault} other than exactly one (zero or more than one).
 *
 * <p>Callers that catch this exception must not silently discard the persisted
 * state. The inconsistency requires explicit user intervention — the application
 * should log a clear error and terminate, prompting the user to inspect and
 * correct {@code workspaces.json} manually before restarting.</p>
 *
 * @see WorkspaceParseException
 */
public class WorkspaceIntegrityException extends WorkspaceException {

    public WorkspaceIntegrityException(String message) {
        super(message);
    }

    public WorkspaceIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
