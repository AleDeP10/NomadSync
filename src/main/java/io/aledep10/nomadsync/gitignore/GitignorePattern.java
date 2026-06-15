package io.aledep10.nomadsync.gitignore;

/**
 * Represents a single entry in a vault's {@code .gitignore} file.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code pattern} — the raw glob pattern, without the {@code !} prefix
 *       (negation state is captured in {@code negated}).</li>
 *   <li>{@code level} — origin classification: {@link PatternLevel#SYSTEM},
 *       {@link PatternLevel#APP}, or {@link PatternLevel#USER}.</li>
 *   <li>{@code appName} — the application that owns this pattern (e.g. {@code "Obsidian"});
 *       {@code null} for SYSTEM and USER patterns.</li>
 *   <li>{@code negated} — {@code true} means the pattern is prefixed with {@code !}
 *       in the file, i.e. "do <em>not</em> ignore this path".</li>
 * </ul>
 *
 * <h2>Mutability</h2>
 * <p>{@code pattern}, {@code level}, and {@code appName} are immutable after construction.
 * {@code negated} is mutable — it is the only user-configurable attribute of a pattern.</p>
 *
 * <h2>Serialisation</h2>
 * <p>When writing the {@code .gitignore} file, the serialiser prepends {@code !} if
 * {@code negated == true}: {@code (negated ? "!" : "") + pattern}.</p>
 */
public class GitignorePattern {

    private final String pattern;
    private final PatternLevel level;
    private final String appName;
    private boolean negated;

    /**
     * Constructs a pattern entry.
     *
     * @param pattern the glob pattern without {@code !} prefix
     * @param level   origin classification
     * @param appName owning application name, or {@code null} for SYSTEM/USER patterns
     * @param negated {@code true} if the pattern should be prefixed with {@code !}
     */
    public GitignorePattern(String pattern, PatternLevel level, String appName, boolean negated) {
        this.pattern = pattern;
        this.level   = level;
        this.appName = appName;
        this.negated = negated;
    }

    /** Returns the glob pattern string, without the {@code !} prefix. */
    public String getPattern()  { return pattern; }

    /** Returns the origin classification of this pattern. */
    public PatternLevel getLevel() { return level; }

    /** Returns the owning application name, or {@code null} for SYSTEM/USER patterns. */
    public String getAppName()  { return appName; }

    /** Returns {@code true} if this pattern should be written with a {@code !} prefix. */
    public boolean isNegated()  { return negated; }

    /**
     * Updates the negated flag — the only user-configurable attribute of a pattern.
     *
     * @param negated {@code true} to prefix the pattern with {@code !} on serialisation
     */
    public void setNegated(boolean negated) { this.negated = negated; }

    @Override
    public String toString() {
        return (negated ? "!" : "") + pattern;
    }
}
