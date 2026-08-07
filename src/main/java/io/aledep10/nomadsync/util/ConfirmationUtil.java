package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.service.LogService;

import java.io.IOException;

/**
 * Interactive {@code y/N} confirmation prompt, shared by every destructive
 * operation that requires explicit user consent before proceeding —
 * {@code vault remove}/{@code relocate}, {@code workspace remove}/
 * {@code relocate}/{@code erase}, and {@code Main}'s own workspace-override
 * fallback confirmation.
 *
 * <p>Not a method on {@code AbstractCli} — {@code Main} is not a subclass of
 * it, and needs the same prompt for its own confirmation (falling back to
 * install-level Git credentials for a workspace with no override file of its
 * own). A static utility is reachable from both without forcing an
 * inheritance relationship that would only exist to share this one method.</p>
 */
public final class ConfirmationUtil {

    private ConfirmationUtil() {}

    /**
     * Outcome of a confirmation attempt — distinct from a simple
     * {@code boolean} because callers need to react differently to a
     * deliberate decline versus a failure to read the response at all (the
     * former is a normal no-op; the latter is reported and logged
     * separately by the caller, typically as a harder failure).
     */
    public enum Result {
        /** {@code --force}-equivalent bypass was set, or the user answered {@code y}/{@code Y}. */
        CONFIRMED,
        /** The user answered anything other than {@code y}/{@code Y} — a deliberate no-op, not an error. */
        DECLINED,
        /** {@link System#in} could not be read — already logged by this method; the caller decides how to fail. */
        INPUT_ERROR
    }

    /**
     * Prints {@code promptMessage} and reads a single character from
     * {@link System#in}, unless {@code bypass} is already {@code true} (the
     * caller's own {@code --force}-equivalent flag), in which case nothing is
     * printed and {@link Result#CONFIRMED} is returned immediately.
     *
     * <p>Any response other than {@code y}/{@code Y} (case-insensitive) is
     * treated as decline — the same default-to-{@code N} convention already
     * in use across every destructive command in this codebase.</p>
     *
     * @param promptMessage the exact text printed to {@code System.out}
     *                      before reading — callers own their own wording,
     *                      this method does not append or format anything
     * @param bypass        the caller's own bypass condition (e.g.
     *                      {@code flags.containsKey(AbstractCli.FLAG_FORCE)});
     *                      evaluated by the caller, not read from a
     *                      hardcoded flag name here — different call sites
     *                      use different flag names for the same purpose
     * @param logService    used only to log the I/O failure case; never
     *                      called for a normal confirm/decline outcome
     * @return {@link Result#CONFIRMED}, {@link Result#DECLINED}, or
     *         {@link Result#INPUT_ERROR}
     */
    public static Result confirm(String promptMessage, boolean bypass, LogService logService) {
        if (bypass) return Result.CONFIRMED;

        System.out.print(promptMessage);

        int response;
        try {
            response = System.in.read();
        } catch (IOException e) {
            logService.error("Failed to read user input: " + e.getMessage(), e);
            return Result.INPUT_ERROR;
        }

        return (response == 'y' || response == 'Y') ? Result.CONFIRMED : Result.DECLINED;
    }
}