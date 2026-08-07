package io.aledep10.nomadsync.config;

import java.util.Map;
import java.util.Set;

/**
 * Governs which properties file(s) a given key may legitimately appear in —
 * the classification settled 2026-08-07: {@code INSTALL_ONLY} keys are
 * machine/process-level and must never appear in a workspace's
 * {@code config.properties}; {@code INSTALL_OR_WORKSPACE} keys may be
 * overridden per workspace, cascading over the install-level value.
 *
 * <p>Deliberately does not track a third, vault-specific tier — the six
 * {@code git.*} keys that vaults can override
 * ({@code remote}/{@code branch}/{@code name}/{@code email}/{@code username}/
 * {@code token}) are typed fields on {@code Vault} itself, resolved ad hoc in
 * {@code GitService} ({@code vault.getGitXxx() ?? loader.get(...)}) — not
 * free-form properties-file entries, so they need no entry here.</p>
 */
public final class ConfigLevelRegistry {

    public enum ConfigLevel { INSTALL_ONLY, INSTALL_OR_WORKSPACE }

    private static final Map<String, ConfigLevel> LEVELS = Map.ofEntries(
            Map.entry(NomadProperties.Marker.MAX_NESTING_DEPTH,  ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.REMOTE,                ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.BRANCH,                ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.NAME,                  ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.EMAIL,                 ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.USERNAME,              ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.TOKEN,                 ConfigLevel.INSTALL_OR_WORKSPACE),
            Map.entry(NomadProperties.Git.EXECUTABLE,            ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Commit.EDITOR,             ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Socket.HOST,               ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Socket.PORT,               ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Socket.RETRY_DELAY,        ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Autosave.INTERVAL_MINUTES, ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Log.WRITERS,               ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Log.PATH,                  ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Log.LEVEL,                 ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Log.SEQ_URL,               ConfigLevel.INSTALL_ONLY),
            Map.entry(NomadProperties.Log.SEQ_API_KEY,           ConfigLevel.INSTALL_ONLY)
    );

    private ConfigLevelRegistry() {}

    /**
     * @param key a property key
     * @return the key's classification, or {@code INSTALL_ONLY} if the key is
     *         unrecognised — an unknown key defaults to the more restrictive
     *         classification rather than silently permitting it anywhere
     */
    public static ConfigLevel levelOf(String key) {
        return LEVELS.getOrDefault(key, ConfigLevel.INSTALL_ONLY);
    }

    /**
     * @param keys candidate keys, e.g. from a just-loaded workspace
     *             {@code config.properties}
     * @return the subset of {@code keys} classified {@code INSTALL_ONLY} —
     *         empty if none are alien to the workspace level
     */
    public static Set<String> findInstallOnlyKeys(Set<String> keys) {
        return keys.stream()
                .filter(k -> levelOf(k) == ConfigLevel.INSTALL_ONLY)
                .collect(java.util.stream.Collectors.toSet());
    }
}