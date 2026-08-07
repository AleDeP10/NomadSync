package io.aledep10.nomadsync.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Format validators for property keys that have a real, checkable shape —
 * deliberately not every key: {@code git.name}/{@code git.token} have no
 * validator (see {@link #validate}'s Javadoc for why), and {@code log.level}
 * is intentionally absent — already enforced by
 * {@link NomadPropertiesLoader#getEnum}, a second check here would be
 * redundant, not additional safety.
 *
 * <p>Shared across every write path — {@code workspace create}/{@code update},
 * {@code vault create}/{@code add}/{@code update}, {@code NomadSync config} —
 * same key, same validator, regardless of which command or which storage
 * (file vs. {@code Vault} field) the value is about to land in.</p>
 */
public final class PropertyValidatorRegistry {

    private static final Map<String, PropertyValidator> VALIDATORS = Map.ofEntries(
            Map.entry(NomadProperties.Marker.MAX_NESTING_DEPTH, PropertyValidatorRegistry::validateNonNegativeInt),
            Map.entry(NomadProperties.Git.EMAIL,      PropertyValidatorRegistry::validateEmail),
            Map.entry(NomadProperties.Git.USERNAME,   PropertyValidatorRegistry::validateGitHubUsername),
            Map.entry(NomadProperties.Git.BRANCH,     PropertyValidatorRegistry::validateRefName),
            Map.entry(NomadProperties.Git.REMOTE,     PropertyValidatorRegistry::validateRefName),
            Map.entry(NomadProperties.Log.WRITERS,    PropertyValidatorRegistry::validateLogWriters),
            Map.entry(NomadProperties.Log.SEQ_URL,    PropertyValidatorRegistry::validateUrl),
            Map.entry(NomadProperties.Socket.PORT,    PropertyValidatorRegistry::validatePort)
            // git.executable intentionally absent from this static map — its
            // validator needs to run a subprocess (git --version), handled as
            // a special case by the caller, not through validate(key, value)
    );

    private PropertyValidatorRegistry() {}

    /**
     * @param key   the property key
     * @param value the candidate value — never blank
     * @return empty if {@code key} has no registered validator, or the value
     *         passes it; otherwise a human-readable reason
     */
    public static Optional<String> validate(String key, String value) {
        PropertyValidator validator = VALIDATORS.get(key);
        return validator == null ? Optional.empty() : validator.validate(value);
    }

    private static Optional<String> validateNonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? Optional.empty() : Optional.of("must be >= 0, got " + parsed);
        } catch (NumberFormatException e) {
            return Optional.of("must be an integer, got '" + value + "'");
        }
    }

    private static Optional<String> validateEmail(String value) {
        // Deliberately not RFC-complete — only rejects the obviously wrong,
        // not every edge case a full email grammar would catch.
        return value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                ? Optional.empty() : Optional.of("does not look like a valid email address");
    }

    private static Optional<String> validateGitHubUsername(String value) {
        return value.matches("^[a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38}$")
                ? Optional.empty()
                : Optional.of("not a valid GitHub username (alphanumeric and single hyphens only, "
                + "max 39 chars, no leading/trailing/double hyphen)");
    }

    private static Optional<String> validateRefName(String value) {
        // Approximation of `git check-ref-format` — no subprocess for
        // branch/remote names, unlike git.executable: rejects the common
        // mistakes (spaces, leading dash, trailing dot/slash) without
        // spawning a process for every call.
        if (value.startsWith("-") || value.endsWith(".") || value.endsWith("/")
                || value.contains(" ") || value.contains("..") || value.contains("~")
                || value.contains("^") || value.contains(":")) {
            return Optional.of("not a valid Git ref name");
        }
        return Optional.empty();
    }

    private static Optional<String> validateLogWriters(String value) {
        String[] tokens = value.split(",");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (!java.util.Set.of("console", "file", "seq").contains(token)) {
                return Optional.of("unknown writer '" + token + "' - expected console, file, or seq");
            }
            if (!seen.add(token)) {
                return Optional.of("duplicate writer '" + token + "'");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateUrl(String value) {
        try {
            new java.net.URI(value);
            return Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return Optional.of("not a valid URL: " + e.getMessage());
        }
    }

    private static Optional<String> validatePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return (port >= 1 && port <= 65535) ? Optional.empty()
                    : Optional.of("must be between 1 and 65535, got " + port);
        } catch (NumberFormatException e) {
            return Optional.of("must be an integer, got '" + value + "'");
        }
    }

    /**
     * Validates {@code git.executable} by actually running it — the only
     * validator here that spawns a process, deliberately excluded from
     * {@link #validate(String, String)}'s static map so every other call stays
     * process-free. Callers must invoke this explicitly for {@code git.executable}
     * specifically, not expect {@link #validate} to dispatch to it.
     *
     * @param path the candidate executable path
     * @return empty if {@code <path> --version} runs successfully, otherwise a
     *         human-readable reason
     */
    public static Optional<String> validateGitExecutable(String path) {
        try {
            Process process = new ProcessBuilder(path, "--version").start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.of("'" + path + " --version' timed out");
            }
            return process.exitValue() == 0 ? Optional.empty()
                    : Optional.of("'" + path + " --version' exited with code " + process.exitValue());
        } catch (java.io.IOException e) {
            return Optional.of("unable to execute '" + path + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted while validating '" + path + "'");
        }
    }

    /**
     * Validates every key in {@code values} via
     * {@link PropertyValidatorRegistry#validate}, collecting every failure into
     * one message — same "report everything at once, not just the first
     * problem" discipline as {@code AbstractMarkerTypeStrategy}'s required-field
     * check. {@code git.executable}, if present, is validated separately via
     * {@link PropertyValidatorRegistry#validateGitExecutable} within the same pass.
     *
     * @param values candidate key-value pairs, e.g. from {@code extractGitFlags}
     *               plus any other flags about to be written
     * @return empty if every value is valid; otherwise one combined message,
     *         one line per failing key
     */
    public static Optional<String> validateAll(Map<String, String> values) {
        List<String> problems = new ArrayList<>();
        values.forEach((key, value) -> {
            Optional<String> problem = NomadProperties.Git.EXECUTABLE.equals(key)
                    ? validateGitExecutable(value)
                    : validate(key, value);
            problem.ifPresent(reason -> problems.add(key + ": " + reason));
        });
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }
}