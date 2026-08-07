package io.aledep10.nomadsync.exception;

/**
 * Thrown when NomadSync's own configuration cannot be loaded or is malformed
 * — install-level ({@code installConfig.properties}, always mandatory) or
 * workspace-level ({@code config.properties} inside a workspace's
 * {@code .nomadsync-workspace/}, optional overrides).
 *
 * <p>Deliberately not a subtype of a project-wide exception root ({@code
 * VaultException}/{@code WorkspaceException} remain independent hierarchies
 * today) — introducing a shared root is a cross-cutting decision affecting
 * all three, not something to settle as a side effect of this one class.</p>
 */
public class ConfigException extends Exception {

    /**
     * @param message human-readable description, including the file path
     *                involved — the sole detail a caller needs to report the
     *                failure without reconstructing context from the cause
     * @param cause   the underlying {@link java.io.IOException}
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}