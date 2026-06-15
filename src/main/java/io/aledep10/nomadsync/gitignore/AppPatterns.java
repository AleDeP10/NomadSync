package io.aledep10.nomadsync.gitignore;

import java.util.List;

/**
 * Groups all {@link GitignorePattern} entries belonging to a single application.
 *
 * <p>Used by {@link io.aledep10.nomadsync.service.GitignoreService} to maintain the
 * per-application structure of the {@code # APP PATTERNS} section in the
 * {@code .gitignore} file. Each instance corresponds to one application block,
 * identified by {@link #getName()} and containing one or more patterns.</p>
 *
 * <h2>Example .gitignore output</h2>
 * <pre>
 * # Obsidian
 * !.obsidian/workspace
 * .obsidian/cache
 * .obsidian/plugins/&#42;/data.json
 * </pre>
 *
 * <h2>Mutability</h2>
 * <p>{@code name} and the {@code patterns} list reference are immutable. Individual
 * {@link GitignorePattern} elements inside the list are mutable — their {@code negated}
 * flag can be updated by the UI and persisted via
 * {@link io.aledep10.nomadsync.service.GitignoreService#save}.</p>
 */
public class AppPatterns {

    private final String name;
    private final List<GitignorePattern> patterns;

    /**
     * Constructs an application pattern group.
     *
     * @param name     application display name (e.g. {@code "Obsidian"})
     * @param patterns the patterns belonging to this application
     */
    public AppPatterns(String name, List<GitignorePattern> patterns) {
        this.name     = name;
        this.patterns = patterns;
    }

    /** Returns the application display name. */
    public String getName() { return name; }

    /**
     * Returns the list of patterns for this application.
     *
     * <p>The list reference is shared — callers must not add or remove elements.
     * Individual pattern {@code negated} flags may be mutated and persisted via
     * {@link io.aledep10.nomadsync.service.GitignoreService#save}.</p>
     *
     * @return the pattern list
     */
    public List<GitignorePattern> getPatterns() { return patterns; }
}
