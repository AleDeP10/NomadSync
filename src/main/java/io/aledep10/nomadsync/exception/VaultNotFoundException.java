package io.aledep10.nomadsync.exception;

/**
 * Thrown when a non-null {@code --vault} flag value does not match any
 * registered vault, either by exact {@code owner/name} slug or by name.
 */
public class VaultNotFoundException extends VaultException {

    public VaultNotFoundException(String vaultFlag) {
        super("vault '" + vaultFlag + "' not found.");
    }
}