package io.aledep10.nomadsync.gitignore;

/**
 * Classifies a {@link GitignorePattern} by its origin and mutability constraints.
 *
 * <ul>
 *   <li>{@link #SYSTEM} — OS-level patterns ({@code .git}, {@code .DS_Store}, etc.).
 *       Always present; negation not supported by design.</li>
 *   <li>{@link #APP} — tool-specific patterns (Obsidian, Logseq, etc.).
 *       Grouped by application in {@link AppPatterns}; negated value is user-configurable.</li>
 *   <li>{@link #USER} — free-form patterns entered directly by the user.
 *       Not validated against any definition list.</li>
 * </ul>
 */
public enum PatternLevel { SYSTEM, APP, USER }
