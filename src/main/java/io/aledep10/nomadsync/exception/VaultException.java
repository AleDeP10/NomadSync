package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.service.VaultService;

/**
 * Base exception for all vault lifecycle errors thrown by {@link VaultService}.
 *
 * <p>Two specialisations exist for distinct failure modes:</p>
 * <ul>
 *   <li>{@link VaultParseException} — the {@code vaults.json} file could not be
 *       read or deserialised; the persisted state is unknown.</li>
 *   <li>{@link VaultIntegrityException} — the file was read successfully but
 *       contains logically inconsistent data (e.g. duplicate {@code repoSlug}
 *       or {@code path}); the persisted state must not be silently discarded.</li>
 * </ul>
 *
 * <p>Callers that need to distinguish between the two failure modes should catch
 * the specialised exceptions before catching {@link VaultException}.</p>
 *
 * @see VaultParseException
 * @see VaultIntegrityException
 */
public class VaultException extends NomadSyncException {

    /**
     * Constructs a {@code VaultException} with the given detail message.
     *
     * @param message description of the failure
     */
    public VaultException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code VaultException} with the given detail message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception that triggered this failure
     */
    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}