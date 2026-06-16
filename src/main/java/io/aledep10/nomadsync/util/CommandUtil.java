package io.aledep10.nomadsync.util;

import io.aledep10.nomadsync.exception.GitException;
import io.aledep10.nomadsync.exception.NetworkException;
import io.aledep10.nomadsync.service.LogService;
import io.aledep10.nomadsync.service.GitService;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for executing shell commands via {@link ProcessBuilder}.
 *
 * <p>Extracted from {@link GitService} to avoid duplication in test helpers
 * and future utilities that need process execution without depending on
 * {@link GitService} directly.</p>
 *
 * <p>All methods are static — this class is not meant to be instantiated.</p>
 *
 * <p>Network-related failures are distinguished from local Git errors by matching
 * the exception message against {@link #NETWORK_PATTERNS}. Matched patterns produce
 * a {@link NetworkException}; everything else produces a {@link GitException}.</p>
 *
 * <h2>Sensitive argument masking</h2>
 * <p>The overload {@link #runCommand(String, List, Set, LogService)} accepts a set
 * of sensitive argument values. Any command argument whose value appears in that set
 * is replaced with {@code <hidden>} in the log line — the actual process receives
 * the real value unchanged. Used by {@link GitService#bootstrapVault} to prevent
 * GitHub tokens from appearing in log output.</p>
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

    /** Placeholder logged in place of sensitive argument values. */
    private static final String HIDDEN = "<hidden>";

    private CommandUtil() {}

    // ── runCommand overloads ──────────────────────────────────────────────────

    /**
     * Executes a command in the given directory and streams output to the log.
     *
     * @param directory     working directory for the command
     * @param command       command and arguments as a list of strings
     * @param logService    logging service — may be {@code null} to suppress output
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(String directory, List<String> command, LogService logService)
            throws GitException, NetworkException, InterruptedException {
        return runCommand(directory, command, Set.of(), logService);
    }

    /**
     * Executes a command in the given directory, masking sensitive argument values
     * in the log while passing them unchanged to the process.
     *
     * <p>Any argument whose exact string value appears in {@code sensitiveArgs}
     * is replaced with {@value #HIDDEN} in the logged command line. The process
     * always receives the original, unmasked arguments.</p>
     *
     * <p>Example — masking a GitHub token embedded in a remote URL:</p>
     * <pre>{@code
     * String remoteUrl = "https://" + token + "@github.com/owner/repo";
     * CommandUtil.runCommand(path,
     *         List.of("git", "remote", "set-url", "origin", remoteUrl),
     *         Set.of(remoteUrl),   // ← remoteUrl logged as <hidden>
     *         logService);
     * }</pre>
     *
     * @param directory     working directory for the command
     * @param command       command and arguments as a list of strings
     * @param sensitiveArgs argument values to mask in log output
     * @param logService    logging service — may be {@code null} to suppress output
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(String directory, List<String> command,
                                 Set<String> sensitiveArgs, LogService logService)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);

        if (logService != null && !sensitiveArgs.isEmpty()) {
            String masked = command.stream()
                    .map(arg -> sensitiveArgs.contains(arg) ? HIDDEN : arg)
                    .collect(Collectors.joining(" "));
            logService.debug("[cmd] " + masked);
        }

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logService != null) logService.info("[cmd] " + line);
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
        return runCommand(directory, command, Set.of(), null);
    }

    /**
     * Executes a command in the given directory and returns its stdout as a string,
     * preserving line breaks.
     *
     * <p>Unlike {@link #runCommandWithOutput(String, List)}, which joins lines
     * without separators (suited for machine-readable single-line output such as
     * {@code git status --porcelain}), this method joins lines with
     * {@link System#lineSeparator()} — suited for human-readable multi-line output
     * such as {@code git status}.</p>
     *
     * @param directory working directory for the command
     * @param command   command and arguments as a list of strings
     * @return stdout output with line breaks preserved, empty string if no output
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static String runCommandWithLines(String directory, List<String> command)
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
                    if (output.length() > 0) output.append(System.lineSeparator());
                    output.append(line);
                }
            }
            process.waitFor();
        } catch (IOException e) {
            if (isNetworkError(e)) throw asNetworkException(e);
            throw asGitException(e);
        }
        return output.toString();
    }

    /**
     * Executes a command in the given directory and returns its stdout as a string.
     *
     * <p>Lines are joined without separators — suited for machine-readable
     * single-line output such as {@code git status --porcelain} or
     * {@code git diff --quiet}. For human-readable multi-line output, use
     * {@link #runCommandWithLines(String, List)} instead.</p>
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
                while ((line = reader.readLine()) != null) output.append(line);
            }
            process.waitFor();
        } catch (IOException e) {
            if (isNetworkError(e)) throw asNetworkException(e);
            throw asGitException(e);
        }
        return output.toString().trim();
    }

    /**
     * Executes a command in the given directory, streaming stdout to a
     * {@link PrintWriter}.
     *
     * @param directory working directory for the command
     * @param command   command and arguments as a list of strings
     * @param writer    destination for stdout lines
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommandToWriter(String directory, List<String> command, PrintWriter writer)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) writer.println(line);
                return process.waitFor();
            } catch (IOException e) {
                if (isNetworkError(e)) throw asNetworkException(e);
                throw asGitException(e);
            }
        } catch (IOException e) {
            throw asNetworkException(e);
        }
    }

    /**
     * Executes a command in the given directory, redirecting stdout directly
     * to {@code outputFile} via the OS — no data passes through the JVM heap.
     *
     * <p>Designed for {@code git show FETCH_HEAD:<file>} where the output may be
     * a large or binary file. Using {@link ProcessBuilder#redirectOutput(File)}
     * avoids loading the content into a {@code byte[]} or reading it line-by-line,
     * which would corrupt binary content.</p>
     *
     * <p>stderr is discarded — the caller must check the exit code to detect
     * failures.</p>
     *
     * @param directory  working directory for the command
     * @param command    command and arguments as a list of strings
     * @param outputFile target file — created or overwritten by the OS
     * @return process exit code
     * @throws GitException         if the process fails with a local error
     * @throws NetworkException     if the process fails with a network error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommandToFile(String directory, List<String> command, Path outputFile)
            throws GitException, NetworkException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectOutput(outputFile.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            if (isNetworkError(e)) throw asNetworkException(e);
            throw asGitException(e);
        }
    }

    // ── Exception classification ──────────────────────────────────────────────

    static boolean isNetworkError(IOException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        return Arrays.stream(NETWORK_PATTERNS).anyMatch(message::contains);
    }

    static NetworkException asNetworkException(IOException e) {
        return new NetworkException("Network error: " + e.getMessage(), e);
    }

    static GitException asGitException(IOException e) {
        return new GitException("Git error: " + e.getMessage(), e);
    }
}