package io.aledep10.nomadsync.exception;

/**
 * Thrown when a non-null {@code --workspace} flag value does not match any
 * registered workspace by {@code workspaceName}.
 */
public class WorkspaceNotFoundException extends WorkspaceException {

    public WorkspaceNotFoundException(String workspaceFlag) {
        super("workspace '" + workspaceFlag + "' not found.");
    }
}
