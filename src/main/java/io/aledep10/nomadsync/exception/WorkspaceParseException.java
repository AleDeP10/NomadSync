package io.aledep10.nomadsync.exception;

/**
 * Thrown by {@code WorkspaceService} when {@code workspaces.json} cannot be
 * read or deserialised — for example, if the file is malformed, locked, or
 * inaccessible due to filesystem permissions.
 *
 * <p>Callers that catch this exception may choose to recover gracefully by
 * starting from an empty registry, since no logical inconsistency in the
 * persisted data has been detected — the file simply could not be read.</p>
 *
 * @see WorkspaceIntegrityException
 */
public class WorkspaceParseException extends WorkspaceException {

    public WorkspaceParseException(String message) {
        super(message);
    }

    public WorkspaceParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
