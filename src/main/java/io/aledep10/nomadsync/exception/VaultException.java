package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.service.VaultService;

/**
 * Thrown by {@link VaultService} when an exception occurs while performing vault
 * operation, such as CRUD or backup.
 */
public class VaultException extends NomadSyncException {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
