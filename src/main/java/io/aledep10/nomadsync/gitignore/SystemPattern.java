package io.aledep10.nomadsync.gitignore;

/**
 * A {@link GitignorePattern} representing an OS-level entry that must always be
 * present in every vault's {@code .gitignore}.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>{@code level} is always {@link PatternLevel#SYSTEM} — fixed at construction.</li>
 *   <li>{@code negated} is always {@code false} — system patterns cannot be negated.
 *       Negating {@code .git} would mean "synchronise the Git internals directory",
 *       which would corrupt the repository. The UI must not expose a negation checkbox
 *       for SYSTEM patterns; {@link io.aledep10.nomadsync.service.GitignoreService#load}
 *       ignores any {@code !} prefix found on a SYSTEM pattern and logs a warning.</li>
 *   <li>{@link #setNegated(boolean)} throws {@link UnsupportedOperationException} —
 *       negation is structurally forbidden, not just discouraged.</li>
 * </ul>
 *
 * <h2>Known SYSTEM patterns</h2>
 * <p>{@code .git}, {@code .DS_Store}, {@code Thumbs.db}, {@code desktop.ini}.</p>
 */
public class SystemPattern extends GitignorePattern {

    /**
     * Constructs a SYSTEM pattern entry.
     *
     * @param pattern the glob pattern (e.g. {@code ".git"})
     * @param level   must be {@link PatternLevel#SYSTEM}
     * @param appName always {@code null} for system patterns
     */
    public SystemPattern(String pattern, PatternLevel level, String appName) {
        super(pattern, level, appName, false);
    }

    /**
     * Always throws {@link UnsupportedOperationException} — SYSTEM patterns cannot
     * be negated. Negation would mean "do not ignore this OS artifact", which is
     * never a valid user intent for system-level entries.
     *
     * @throws UnsupportedOperationException unconditionally
     */
    @Override
    public void setNegated(boolean negated) {
        throw new UnsupportedOperationException(
                "SYSTEM patterns cannot be negated: " + getPattern());
    }
}
