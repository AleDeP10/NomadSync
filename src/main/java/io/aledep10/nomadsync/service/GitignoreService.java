package io.aledep10.nomadsync.service;

import io.aledep10.nomadsync.gitignore.AppPatterns;
import io.aledep10.nomadsync.gitignore.GitignorePattern;
import io.aledep10.nomadsync.gitignore.PatternLevel;
import io.aledep10.nomadsync.gitignore.SystemPattern;
import io.aledep10.nomadsync.gitignore.VaultPatterns;
import io.aledep10.nomadsync.gitignore.exception.GitignoreException;
import io.aledep10.nomadsync.util.ValidationUtil;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the {@code .gitignore} file for a vault — stateless by design.
 *
 * <p>No mutable instance state is held between calls. Every method receives
 * a {@code vaultPath}, reads and writes the {@code .gitignore} file within
 * that path, and returns a fresh result. This makes the service thread-safe
 * by construction — multiple vaults can call it concurrently without
 * synchronisation.</p>
 *
 * <h2>Pattern hierarchy</h2>
 * <ul>
 *   <li>{@link PatternLevel#SYSTEM} — OS-level entries ({@code .git},
 *       {@code .DS_Store}, etc.) always present. Cannot be negated.</li>
 *   <li>{@link PatternLevel#APP} — tool-specific entries (Obsidian, Logseq,
 *       etc.) grouped by application. Negated flag is user-configurable.</li>
 *   <li>{@link PatternLevel#USER} — free-form entries entered by the user.
 *       Any line not recognised as SYSTEM or APP is classified here.</li>
 * </ul>
 *
 * <h2>File format</h2>
 * <pre>
 * # SYSTEM PATTERNS - DO NOT TOUCH!
 * .git
 * .DS_Store
 * ...
 *
 *
 * # APP PATTERNS
 *
 * # Obsidian
 * !.obsidian/workspace
 * .obsidian/cache
 * ...
 *
 *
 * # USER PATTERNS
 * *.tmp
 * </pre>
 *
 * <h2>Constructor argument order</h2>
 * <p>Follows the project convention: {@link LogService} last.</p>
 */
public class GitignoreService {

    // ── Definitions ───────────────────────────────────────────────────────────

    static final List<SystemPattern> SYSTEM_PATTERN_DEFINITIONS = List.of(
            new SystemPattern(".git",        PatternLevel.SYSTEM, null),
            new SystemPattern(".DS_Store",   PatternLevel.SYSTEM, null),
            new SystemPattern("Thumbs.db",   PatternLevel.SYSTEM, null),
            new SystemPattern("desktop.ini", PatternLevel.SYSTEM, null)
    );

    static final List<AppPatterns> APP_PATTERN_DEFINITIONS = List.of(
            createAppPatterns("Obsidian", List.of(
                    "!.obsidian/workspace",
                    ".obsidian/cache",
                    ".obsidian/plugins/*/data.json",
                    ".obsidian/graph.json",
                    ".obsidian/starred.json"
            )),
            createAppPatterns("Dataview", List.of(
                    ".obsidian/plugins/dataview/data.json"
            )),
            createAppPatterns("Templater", List.of(
                    "!templates/",
                    ".obsidian/plugins/templater-obsidian/data.json"
            )),
            createAppPatterns("Logseq", List.of(
                    ".logseq/bak/",
                    ".logseq/version-files/"
            ))
    );

    private final LogService logService;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Constructs the service.
     *
     * @param logService shared logging service
     */
    public GitignoreService(LogService logService) {
        this.logService = logService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Loads the {@code .gitignore} file for the given vault, classifies every
     * line into SYSTEM / APP / USER, restores any missing SYSTEM patterns,
     * and rewrites the file in canonical three-section format.
     *
     * <p>If the file does not exist, it is created from scratch with all
     * SYSTEM and APP default patterns.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @return a {@link VaultPatterns} instance with all three sections populated
     * @throws GitignoreException if the file cannot be read or written
     */
    public VaultPatterns load(Path vaultPath) throws GitignoreException {
        Map<String, ParsedLine> parsedLines = parseGitignoreFile(vaultPath.resolve(".gitignore"));

        // SYSTEM — restore any missing patterns, consume matched ones
        List<SystemPattern> system = cloneSystemPatterns();
        system.forEach(pattern -> {
            if (parsedLines.containsKey(pattern.getPattern())) {
                parsedLines.remove(pattern.getPattern());
            } else {
                logService.warn("Missing SYSTEM pattern: " + pattern.getPattern());
            }
        });

        // APP — reconcile negated flag from file, consume matched ones
        List<AppPatterns> app = cloneAppPatterns();
        app.forEach(appPatterns ->
                appPatterns.getPatterns().forEach(pattern -> {
                    if (parsedLines.containsKey(pattern.getPattern())) {
                        pattern.setNegated(parsedLines.get(pattern.getPattern()).isNegated());
                        parsedLines.remove(pattern.getPattern());
                    }
                    // if not found: keeps the APP_PATTERN_DEFINITIONS default
                }));

        // USER — everything not consumed by SYSTEM or APP
        List<GitignorePattern> user = parsedLines.values().stream()
                .map(pl -> new GitignorePattern(pl.pattern(), PatternLevel.USER, null, pl.isNegated()))
                .toList();

        // rewrite in canonical format
        writeGitignore(vaultPath, serializeGitignore(system, app, user));

        return new VaultPatterns(system, app, user);
    }

    /**
     * Persists a modified pattern list back to the vault's {@code .gitignore}.
     *
     * <p>SYSTEM patterns are extracted and cast safely via stream filter —
     * any SYSTEM pattern flagged as negated is logged as a warning and ignored
     * (negation is structurally forbidden by {@link SystemPattern#setNegated}).
     * APP patterns are reconciled with a fresh clone of
     * {@link #APP_PATTERN_DEFINITIONS} to preserve grouping. USER patterns
     * replace the existing USER section entirely.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @param patterns  the full pattern list to persist —
     *                  typically {@link VaultPatterns#allPatterns()}
     * @throws GitignoreException if the file cannot be written
     */
    public void save(Path vaultPath, List<GitignorePattern> patterns) throws GitignoreException {
        Map<PatternLevel, List<GitignorePattern>> partitioned = patterns.stream()
                .collect(Collectors.groupingBy(GitignorePattern::getLevel));

        List<GitignorePattern> systemRaw = partitioned.getOrDefault(PatternLevel.SYSTEM, List.of());
        List<GitignorePattern> appRaw    = partitioned.getOrDefault(PatternLevel.APP,    List.of());
        List<GitignorePattern> user      = partitioned.getOrDefault(PatternLevel.USER,   List.of());

        // SYSTEM — safe cast via stream; warn on negated (should never happen via UI)
        List<SystemPattern> system = systemRaw.stream()
                .filter(SystemPattern.class::isInstance)
                .map(SystemPattern.class::cast)
                .peek(p -> {
                    if (p.isNegated()) logService.warn(
                            "Cannot negate SYSTEM pattern: " + p.getPattern());
                })
                .collect(Collectors.toList());

        // APP — clone definitions and apply user-provided negated flags
        List<AppPatterns> apps = cloneAppPatterns();
        Map<String, Boolean> appNegations = appRaw.stream()
                .collect(Collectors.toMap(
                        GitignorePattern::getPattern,
                        GitignorePattern::isNegated,
                        (existing, replacement) -> replacement));
        apps.forEach(appPatterns ->
                appPatterns.getPatterns().forEach(pattern -> {
                    if (appNegations.containsKey(pattern.getPattern())) {
                        pattern.setNegated(appNegations.get(pattern.getPattern()));
                    }
                }));

        writeGitignore(vaultPath, serializeGitignore(system, apps, user));
    }

    /**
     * Returns a list of compiled {@link PathMatcher} instances for all
     * non-negated patterns in the vault's {@code .gitignore}.
     *
     * <p>Used by {@link VaultService#makeVaultSnapshot} to exclude paths
     * during the FIFO backup walk. A negated pattern ({@code !}) means
     * "do not ignore this" — it must be <em>included</em> in the snapshot,
     * so it is excluded from the matcher list.</p>
     *
     * @param vaultPath absolute path to the vault directory
     * @return matchers for all non-negated active patterns
     * @throws GitignoreException if {@link #load} fails
     */
    public List<PathMatcher> forSnapshot(Path vaultPath) throws GitignoreException {
        VaultPatterns vp = load(vaultPath);
        return Stream.concat(
                        vp.getSystem().stream(),
                        Stream.concat(
                                vp.getApp().stream().flatMap(a -> a.getPatterns().stream()),
                                vp.getUser().stream()))
                .filter(p -> !p.isNegated())
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p.getPattern()))
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Parses the {@code .gitignore} file into a mutable {@link TreeMap} keyed
     * by the cleaned pattern string (without {@code !} prefix).
     *
     * <p>Returns an empty map if the file does not exist. Blank lines and
     * comment lines (including those with leading whitespace before {@code #})
     * are silently ignored. On duplicate keys, the last occurrence wins
     * ({@code TreeMap.put} overwrites).</p>
     *
     * @param gitignorePath absolute path to the {@code .gitignore} file
     * @return mutable map of pattern → {@link ParsedLine}
     * @throws GitignoreException if the file exists but cannot be read
     */
    private Map<String, ParsedLine> parseGitignoreFile(Path gitignorePath)
            throws GitignoreException {
        ValidationUtil.requireNonNull(gitignorePath, "gitignorePath");
        Map<String, ParsedLine> result = new TreeMap<>();
        if (!gitignorePath.toFile().exists()) return result;
        try {
            Files.readAllLines(gitignorePath).stream()
                    .filter(line -> !line.isBlank() && !line.trim().startsWith("#"))
                    .forEach(line -> {
                        ParsedLine parsed = cleanLine(line);
                        result.put(parsed.pattern(), parsed);
                    });
        } catch (IOException e) {
            throw new GitignoreException("Unable to read .gitignore", e);
        }
        return result;
    }

    /**
     * Parses a single raw line into a {@link ParsedLine}.
     *
     * <p>Leading and trailing whitespace is trimmed. If the trimmed line starts
     * with {@code !}, negated is {@code true} and the {@code !} is stripped from
     * the pattern. Whitespace between {@code !} and the pattern is also trimmed.</p>
     *
     * @param rawLine a single non-blank, non-comment line from the file
     * @return a {@link ParsedLine} with cleaned pattern and negated flag
     */
    private ParsedLine cleanLine(String rawLine) {
        boolean negated = rawLine.trim().startsWith("!");
        String pattern  = negated ? rawLine.trim().substring(1).trim() : rawLine.trim();
        return new ParsedLine(pattern, negated);
    }

    /**
     * Returns a deep clone of {@link #SYSTEM_PATTERN_DEFINITIONS}.
     *
     * <p>Each element is a new {@link SystemPattern} instance — mutations to
     * the returned list (or to individual pattern fields) do not affect the
     * static definitions. {@link SystemPattern#setNegated} is structurally
     * forbidden, so isNegated is always {@code false}.</p>
     */
    private List<SystemPattern> cloneSystemPatterns() {
        return SYSTEM_PATTERN_DEFINITIONS.stream()
                .map(p -> new SystemPattern(p.getPattern(), p.getLevel(), p.getAppName()))
                .toList();
    }

    /**
     * Returns a deep clone of {@link #APP_PATTERN_DEFINITIONS}.
     *
     * <p>Each {@link AppPatterns} and each inner {@link GitignorePattern} is a
     * new instance — mutations (e.g. {@code setNegated}) affect only the clone,
     * not the static definitions.</p>
     */
    private List<AppPatterns> cloneAppPatterns() {
        return APP_PATTERN_DEFINITIONS.stream()
                .map(ap -> new AppPatterns(ap.getName(),
                        ap.getPatterns().stream()
                                .map(p -> new GitignorePattern(
                                        p.getPattern(), p.getLevel(), p.getAppName(), p.isNegated()))
                                .toList()))
                .toList();
    }

    /**
     * Serialises the three pattern sections into the canonical {@code .gitignore}
     * string format with section headers and blank line separators.
     *
     * @param system SYSTEM patterns
     * @param apps   APP pattern groups
     * @param user   USER patterns
     * @return the complete file content as a string
     */
    private String serializeGitignore(List<SystemPattern> system,
                                      List<AppPatterns> apps,
                                      List<GitignorePattern> user) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SYSTEM PATTERNS - DO NOT TOUCH!\n");
        system.forEach(p -> sb.append(p.isNegated() ? "!" : "").append(p.getPattern()).append("\n"));

        sb.append("\n\n\n# APP PATTERNS\n");
        apps.forEach(app -> {
            sb.append("\n# ").append(app.getName()).append("\n");
            app.getPatterns().forEach(p ->
                    sb.append(p.isNegated() ? "!" : "").append(p.getPattern()).append("\n"));
        });

        sb.append("\n\n\n# USER PATTERNS\n");
        user.forEach(p -> sb.append(p.isNegated() ? "!" : "").append(p.getPattern()).append("\n"));
        return sb.toString();
    }

    /**
     * Writes the serialised content to {@code vaultPath/.gitignore}.
     *
     * @param vaultPath absolute path to the vault directory
     * @param content   serialised file content
     * @throws GitignoreException if the file cannot be written
     */
    private void writeGitignore(Path vaultPath, String content) throws GitignoreException {
        try {
            Files.write(vaultPath.resolve(".gitignore"), content.getBytes());
        } catch (IOException e) {
            throw new GitignoreException("Unable to write .gitignore", e);
        }
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    private static AppPatterns createAppPatterns(String name, List<String> rawPatterns) {
        return new AppPatterns(name, rawPatterns.stream()
                .map(s -> new GitignorePattern(
                        extractPattern(s), PatternLevel.APP, name, extractNegate(s)))
                .toList());
    }

    private static String extractPattern(String s) {
        return s.trim().startsWith("!") ? s.trim().substring(1) : s.trim();
    }

    private static boolean extractNegate(String s) {
        return s.trim().startsWith("!");
    }

    // ── Internal DTO ─────────────────────────────────────────────────────────

    /** Represents a single parsed line from the {@code .gitignore} file. */
    private record ParsedLine(String pattern, boolean isNegated) {}
}