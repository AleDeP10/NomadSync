package io.aledep10.nomadsync.cli;

import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.util.StringUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Shared flag-validation machinery for every domain CLI ({@code VaultCli},
 * {@code WorkspaceCli}) — the four helpers ({@link #hasUnknownFlags},
 * {@link #hasBlankRequiredFlags}, {@link #hasBlankOptionalValue},
 * {@link #nearestKnownFlag}) were previously duplicated verbatim in
 * {@code VaultCli} and would otherwise have been duplicated again in
 * {@code WorkspaceCli}.
 *
 * <h2>What is deliberately <strong>not</strong> here</h2>
 * <p>{@code execute(String, Map, List, ...)} is not part of this contract,
 * not even as an abstract method. Each concrete CLI's {@code execute} is a
 * {@code switch} over its own subcommand names, dispatching to its own
 * {@code handle*} methods — there is no shared behaviour to factor out, only
 * a coincidental similarity of signature shape. Nothing in the codebase ever
 * holds a polymorphic {@code AbstractCli} reference and calls {@code execute}
 * without already knowing which concrete CLI it is — {@code Main} decides
 * which one to build before ever reaching a call site. Declaring it here
 * would be a contract with no shared body and no polymorphic caller to
 * justify it.</p>
 *
 * <p>{@code handle*} methods stay {@code private} (or package-private) on
 * each concrete subclass, not promoted to a public contract here either. A
 * future UI consumer (v2.0 main window) would call the corresponding
 * {@code *Service} method directly with typed parameters — it has no natural
 * use for a {@code Map<String, String>} of CLI flags, so making the CLI's own
 * flag-handling layer public would serve a consumer that doesn't exist.</p>
 */
public abstract class AbstractCli {

    public static final String FLAG_FORCE = "force";
    public static final String FLAG_SUBCOMMAND = "sub";

    public static final String FLAG_PATH = "path";
    public static final String GIT_FLAG_PREFIX = "git.";
    public static final String FLAG_GIT_NAME = GIT_FLAG_PREFIX + "name";
    public static final String FLAG_GIT_EMAIL = GIT_FLAG_PREFIX + "email";
    public static final String FLAG_GIT_USERNAME = GIT_FLAG_PREFIX + "username";
    public static final String FLAG_GIT_TOKEN = GIT_FLAG_PREFIX + "token";
    public static final String FLAG_GIT_BRANCH = GIT_FLAG_PREFIX + "branch";
    public static final String FLAG_GIT_REMOTE = GIT_FLAG_PREFIX + "remote";

    protected final LogService logService;

    /**
     * @param logService shared logging service — the only dependency common
     *                   to every instance method here; each concrete
     *                   subclass's own domain-specific dependencies
     *                   ({@code VaultService}, {@code GitService}, etc.) are
     *                   received by that subclass's own constructor, not here
     */
    protected AbstractCli(LogService logService) {
        this.logService = logService;
    }

    /**
     * Detects any flag keys in {@code flags} that do not belong to the given
     * known set for the current subcommand.
     *
     * <p>The internal {@code "sub"} key injected by the parser is always
     * permitted and never reported. For each unrecognised key, logs one error
     * line — including a "did you mean...?" suggestion (via
     * {@link #nearestKnownFlag}) when a known flag is within Levenshtein
     * distance {@link #flagSuggestionMaxDistance()}, to help catch typos.</p>
     *
     * @param flags      parsed CLI flags (global flags already removed)
     * @param knownFlags set of keys valid for the current subcommand
     * @param handler    handler name used as log prefix, e.g. {@code "handleVaultAdd"}
     * @return {@code true} if at least one unrecognised key is present,
     *         {@code false} if all keys are recognised
     */
    protected boolean hasUnknownFlags(Map<String, String> flags, Set<String> knownFlags, String handler) {
        List<String> unknown = flags.keySet().stream()
                .filter(k -> !k.equals(FLAG_SUBCOMMAND) && !knownFlags.contains(k))
                .sorted()
                .toList();
        if (unknown.isEmpty()) return false;

        unknown.forEach(k -> {
            Optional<String> suggestion = nearestKnownFlag(k, knownFlags);
            String message = "unknown flag '--" + k + "'"
                    + suggestion.map(s -> " — did you mean '--" + s + "'?").orElse("");
            logService.error(handler + ": " + message);
        });
        return true;
    }

    /**
     * Detects required flags that are either entirely absent from {@code flags} or
     * present with a blank value — both are treated as the same violation: the
     * caller did not supply a real value for a field that cannot be meaningfully
     * empty.
     *
     * <p>Intended for structural flags ({@code --vault}, {@code --owner},
     * {@code --name}, {@code --path}, {@code --workspaceName}, ...) that must
     * always resolve to a real value. Do <strong>not</strong> use this for
     * {@code --git.*}-style flags — a blank override is a deliberately
     * supported way to clear a per-entity value, not an error.</p>
     *
     * <p>Each invalid key produces its own log line, using a known syntax hint
     * from {@link #syntaxHints()} when available (e.g.
     * {@code --vault=<name|owner/name>}) so the message conveys the expected
     * format, not just that the flag is missing.</p>
     *
     * @param flags        parsed CLI flags
     * @param requiredKeys the set of flag keys that must be present and non-blank
     * @param handler      handler name used as log prefix, e.g. {@code "handleVaultUpdate"}
     * @return {@code true} if at least one required key is absent or blank,
     *         {@code false} if all are present with a real value
     */
    public boolean hasBlankRequiredFlags(Map<String, String> flags, Set<String> requiredKeys, String handler) {
        List<String> invalid = requiredKeys.stream()
                .filter(k -> !flags.containsKey(k) || flags.get(k).isBlank())
                .sorted()
                .toList();
        if (invalid.isEmpty()) return false;
        invalid.forEach(k -> {
            String hint = syntaxHints().getOrDefault(k, "--" + k + "=<value>");
            logService.error(handler + ": requires " + hint);
        });
        return true;
    }

    /**
     * Known syntax hints for required flags whose expected format is not
     * obvious from the key name alone — used by {@link #hasBlankRequiredFlags}
     * to produce an actionable error message instead of a generic "cannot be
     * blank". Keys absent from this map fall back to a generic
     * {@code --key=<value>} hint.
     */
    protected abstract Map<String, String> syntaxHints();

    /**
     * Detects structural flags that are present in {@code flags} but hold a
     * blank value — unlike {@link #hasBlankRequiredFlags}, absence is not a
     * violation here: these keys are legitimately optional (e.g. {@code --path}
     * on {@code vault update}, left out to mean "don't touch it"). Only
     * "provided but empty" is treated as user error, since a blank structural
     * value never has a meaningful interpretation.
     *
     * @param flags          parsed CLI flags
     * @param structuralKeys structural keys to check when present (e.g.
     *                       {@code owner}, {@code name}, {@code path}) — the
     *                       caller's full set for the current handler; may be
     *                       empty if the handler has none
     * @param handler        handler name used as log prefix, e.g. {@code "handleVaultUpdate"}
     * @return {@code true} if at least one checked key is present but blank,
     *         {@code false} otherwise
     */
    public boolean hasBlankOptionalValue(Map<String, String> flags, Set<String> structuralKeys, String handler) {
        List<String> blank = structuralKeys.stream()
                .filter(flags::containsKey)
                .filter(k -> flags.get(k).isBlank())
                .sorted()
                .toList();
        if (blank.isEmpty()) return false;
        blank.forEach(k -> logService.error(handler + ": --" + k + " was provided but has no value"));
        return true;
    }

    /**
     * Finds the closest known flag to an unrecognized one, by Levenshtein
     * distance — used to produce a "did you mean...?" hint. Returns empty if no
     * known flag is within {@link #flagSuggestionMaxDistance()}, avoiding a
     * misleading suggestion for a flag that is simply unrelated (e.g. belongs to
     * a different command entirely) rather than a typo.
     *
     * @param unknownFlag the flag key that was not recognized
     * @param knownFlags  the set of valid flag keys for the current command
     * @return the nearest match within threshold, or empty if none qualifies
     */
    protected Optional<String> nearestKnownFlag(String unknownFlag, Set<String> knownFlags) {
        return knownFlags.stream()
                .map(known -> Map.entry(known, StringUtil.levenshteinDistance(unknownFlag, known)))
                .filter(e -> e.getValue() <= flagSuggestionMaxDistance())
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /**
     * Maximum edit distance for a "did you mean...?" suggestion to be shown for
     * this CLI's own flags — left to each subclass because it depends on the
     * length and shape of that CLI's specific flag names, not on anything the
     * superclass knows.
     */
    protected abstract int flagSuggestionMaxDistance();


    protected Map<String, String> extractGitFlags(Map<String, String> flags) {
        Map<String, String> gitFlags = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (k.startsWith(GIT_FLAG_PREFIX)) gitFlags.put(k, v);
        });
        return gitFlags;
    }

    /**
     * Returns {@code true} if {@code source} and {@code target} live on different
     * filesystems/drives — cross-drive relocate is not supported in this version
     * (see the check in {@link VaultCli#handleVaultRelocate}).
     *
     * <p>{@code target} need not exist yet — walks up to the nearest existing
     * ancestor to determine its file store, since a not-yet-created path has none
     * of its own. {@code source} is expected to already exist (the vault/workspace
     * being relocated) — callers must not pass a nonexistent source.</p>
     *
     * @throws IOException if the file store cannot be determined for either path
     */
    protected boolean isCrossDrive(Path source, Path target) throws IOException {
        Path targetAnchor = target;
        while (!Files.exists(targetAnchor) && targetAnchor.getParent() != null) {
            targetAnchor = targetAnchor.getParent();
        }
        return !Files.getFileStore(source).equals(Files.getFileStore(targetAnchor));
    }
}