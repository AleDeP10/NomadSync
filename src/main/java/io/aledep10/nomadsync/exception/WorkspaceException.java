package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.service.WorkspaceService;

/**
 * Base exception for all workspace lifecycle errors thrown by {@link WorkspaceService}.
 *
 * <p>Two specialisations exist for distinct failure modes, mirroring
 * {@link VaultException}:</p>
 * <ul>
 *   <li>{@link WorkspaceParseException} — {@code workspaces.json} could not be
 *       read or deserialised; the persisted state is unknown.</li>
 *   <li>{@link WorkspaceIntegrityException} — the file was read successfully but
 *       contains logically inconsistent data (e.g. duplicate {@code workspaceName},
 *       or more/less than exactly one entry marked {@code isDefault}).</li>
 * </ul>
 *
 * <p>No {@code WorkspaceAmbiguousException} equivalent to {@link VaultAmbiguousException}
 * exists: a vault's bare-name resolution can be ambiguous because {@code owner}+{@code name}
 * are two distinct dimensions, but {@code workspaceName} alone is already the unique
 * identity of a workspace entry ({@code NomadSync-WSP-001}) — a bare-name lookup can
 * only ever match zero or one entry.</p>
 *
 * @see WorkspaceParseException
 * @see WorkspaceIntegrityException
 * @see WorkspaceNotFoundException
 */
public class WorkspaceException extends NomadSyncException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
