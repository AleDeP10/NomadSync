package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.GitService;

import java.io.*;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for executing shell commands via {@link ProcessBuilder}.
 *
 * <p>Extracted from {@link GitService} to avoid
 * duplication in test helpers and future utilities that need process execution
 * without depending on GitService directly.</p>
 *
 * <p>All methods are static — this class is not meant to be instantiated.</p>
 *
 * <p>Network-related failures are distinguished from local Git errors by matching
 * the exception message against {@link #NETWORK_PATTERNS}. Matched patterns produce
 * a {@link NetworkException}; everything else produces a {@link GitException}.</p>
 */
public final class CommandUtil {

    /**
     * Stderr patterns that identify network-related Git failures.
     * Any {@link IOException} whose message contains one of these strings is wrapped
     * in a {@link NetworkException}; all others become {@link GitException}.
     */
    static final String[] NETWORK_PATTERNS = {
            "timeout",
            "Could not resolve host",
            "Connection refused",
            "Failed to connect",
            "Network is unreachable"
    };

    private CommandUtil() {
        // utility class — no instances
    }

    /**
     * Executes a command in the given directory and streams output to the log.
     *
     * @param directory  working directory for the command
     * @param command    command and arguments as a list of strings
     * @param logService logging service — may be {@code null} to suppress output
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(String directory, List<String> command, LogService logService)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logService != null) {
                        logService.info("[cmd] " + line);
                    }
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            if (isNetworkError(e)) throw asNetworkException(e);
            throw asGitException(e);
        }
    }

    /**
     * Executes a command in the given directory and returns its exit code.
     * Output is suppressed. Convenience overload for test helpers.
     *
     * @param directory working directory for the command
     * @param command   command and arguments as a list of strings
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(String directory, List<String> command)
            throws GitException, NetworkException, InterruptedException {
        return runCommand(directory, command, null);
    }

    /**
     * Executes a command in the given directory and returns its stdout as a string.
     *
     * <p>Used when the content of the output matters, not just the exit code.</p>
     *
     * @param directory working directory for the command
     * @param command   command and arguments as a list of strings
     * @return trimmed stdout output, empty string if no output
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static String runCommandWithOutput(String directory, List<String> command)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
        } catch (IOException e) {
            if (isNetworkError(e)) throw asNetworkException(e);
            throw asGitException(e);
        }
        return output.toString().trim();
    }

    public static int runCommandToWriter(
            String directory, List<String> command, PrintWriter writer)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                //PrintStream printStream = new PrintStream(writer);
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
                return process.waitFor();
            } catch (IOException e) {
                if (isNetworkError(e)) throw asNetworkException(e);
                throw asGitException(e);
            }
        } catch (IOException e) {
            throw asNetworkException(e);
        }
    }

    // ── Exception classification ──────────────────────────────────────────────

    /**
     * Returns {@code true} if the given exception message matches a known
     * network error pattern.
     *
     * @param e the exception to inspect
     * @return {@code true} if the message contains a network-related pattern
     */
    static boolean isNetworkError(IOException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        return Arrays.stream(NETWORK_PATTERNS).anyMatch(message::contains);
    }

    /**
     * Wraps an {@link IOException} in a {@link NetworkException}.
     *
     * @param e the original exception
     * @return a new {@link NetworkException} with the original as cause
     */
    static NetworkException asNetworkException(IOException e) {
        return new NetworkException("Network error: " + e.getMessage(), e);
    }

    /**
     * Wraps an {@link IOException} in a {@link GitException}.
     *
     * @param e the original exception
     * @return a new {@link GitException} with the original as cause
     */
    static GitException asGitException(IOException e) {
        return new GitException("Git error: " + e.getMessage(), e);
    }
}
