package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.service.VaultService;

/**
 * Thrown by {@link VaultService} when the {@code vaults.json} file cannot be
 * read or deserialised — for example, if the file is malformed, locked, or
 * inaccessible due to filesystem permissions.
 *
 * <p>Callers that catch this exception may choose to recover gracefully by
 * starting from an empty vault state, since no logical inconsistency in the
 * persisted data has been detected — the file simply could not be read.</p>
 *
 * @see VaultIntegrityException
 */
public class VaultParseException extends VaultException {

    /**
     * Constructs a {@code VaultParseException} with the given detail message.
     *
     * @param message description of the parse failure
     */
    public VaultParseException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code VaultParseException} with the given detail message and cause.
     *
     * @param message description of the parse failure
     * @param cause   the underlying {@link java.io.IOException} that triggered this failure
     */
    public VaultParseException(String message, Throwable cause) {
        super(message, cause);
    }
}