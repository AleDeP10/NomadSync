package io.aledep10.nomadsync.exception;

import io.aledep10.nomadsync.vault.Vault;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a bare-name {@code --vault} flag matches more than one
 * registered vault, requiring disambiguation via {@code owner/name}.
 */
public class VaultAmbiguousException extends VaultException {

    public VaultAmbiguousException(String vaultFlag, List<Vault> matches) {
        super("name '" + vaultFlag + "' is ambiguous. Matches: "
                + matches.stream().map(Vault::getRepoSlug).collect(Collectors.joining(", "))
                + ". Use --vault=<owner>/<name>");
    }
}
