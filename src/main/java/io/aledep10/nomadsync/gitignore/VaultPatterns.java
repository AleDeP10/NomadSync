package io.aledep10.nomadsync.gitignore;

import java.util.List;

/**
 * Container returned by {@link io.aledep10.nomadsync.service.GitignoreService#load}
 * grouping the three independent sections of a vault's {@code .gitignore}.
 *
 * <h2>Why three separate lists?</h2>
 * <p>Each section has different semantics and constraints:</p>
 * <ul>
 *   <li>{@link #getSystem()} — OS patterns that must always be present;
 *       returned as {@link SystemPattern} instances which enforce non-negation.</li>
 *   <li>{@link #getApp()} — tool-specific patterns grouped by application;
 *       returned as {@link AppPatterns} instances that preserve the group structure
 *       needed for the UI accordion and for per-app enable/disable toggles.</li>
 *   <li>{@link #getUser()} — free-form user patterns with no grouping constraint.</li>
 * </ul>
 *
 * <p>Consumers that need a flat stream of all patterns can call
 * {@link #allPatterns()} which concatenates the three sections in canonical order:
 * SYSTEM → APP (flattened) → USER.</p>
 *
 * <h2>Mutability</h2>
 * <p>The list references are immutable (set at construction). Individual
 * {@link GitignorePattern} elements inside each list are mutable — their
 * {@code negated} flag can be updated and passed back to
 * {@link io.aledep10.nomadsync.service.GitignoreService#save}.</p>
 */
public class VaultPatterns {

    private final List<SystemPattern>  system;
    private final List<AppPatterns>    app;
    private final List<GitignorePattern> user;

    /**
     * Constructs the container with the three sections.
     *
     * @param system SYSTEM patterns — never null, never empty in a valid vault
     * @param app    APP pattern groups — never null, may be empty
     * @param user   USER patterns — never null, may be empty
     */
    public VaultPatterns(List<SystemPattern> system,
                         List<AppPatterns> app,
                         List<GitignorePattern> user) {
        this.system = system;
        this.app    = app;
        this.user   = user;
    }

    /**
     * Returns the SYSTEM pattern list.
     *
     * @return unmodifiable-by-contract list of {@link SystemPattern} instances
     */
    public List<SystemPattern> getSystem() { return system; }

    /**
     * Returns the APP pattern groups.
     *
     * @return list of {@link AppPatterns}, one per registered application
     */
    public List<AppPatterns> getApp() { return app; }

    /**
     * Returns the USER pattern list.
     *
     * @return list of free-form {@link GitignorePattern} instances
     */
    public List<GitignorePattern> getUser() { return user; }

    /**
     * Returns a flat stream of all patterns in canonical order:
     * SYSTEM → APP (flattened across groups) → USER.
     *
     * <p>Convenience method for callers that need to iterate all patterns
     * without caring about section boundaries — e.g. when building the
     * full flat list to pass to
     * {@link io.aledep10.nomadsync.service.GitignoreService#save}.</p>
     *
     * @return concatenated list: system + app (flat) + user
     */
    public List<GitignorePattern> allPatterns() {
        java.util.ArrayList<GitignorePattern> all = new java.util.ArrayList<>();
        all.addAll(system);
        app.forEach(a -> all.addAll(a.getPatterns()));
        all.addAll(user);
        return all;
    }
}
