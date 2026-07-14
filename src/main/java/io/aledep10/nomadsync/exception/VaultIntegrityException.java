package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.service.VaultService;

/**
 * Thrown by {@link VaultService} when the {@code catalog.json} file is readable
 * but contains logically inconsistent data — for example, two vaults sharing
 * the same {@code repoSlug} or the same local {@code path}.
 *
 * <p>Callers that catch this exception must not silently discard the persisted
 * state. The inconsistency requires explicit user intervention — the application
 * should log a clear error and terminate, prompting the user to inspect and
 * correct {@code catalog.json} manually before restarting.</p>
 *
 * @see VaultParseException
 */
public class VaultIntegrityException extends VaultException {

    /**
     * Constructs a {@code VaultIntegrityException} with the given detail message.
     *
     * @param message description of the integrity violation
     */
    public VaultIntegrityException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code VaultIntegrityException} with the given detail message and cause.
     *
     * @param message description of the integrity violation
     * @param cause   the underlying exception that triggered this failure
     */
    public VaultIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}