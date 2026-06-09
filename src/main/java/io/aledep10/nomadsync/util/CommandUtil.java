package io.aledep10.nomadSync.util;

import io.aledep10.nomadSync.service.LogService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Utility class for executing shell commands via {@link ProcessBuilder}.
 *
 * <p>Extracted from {@link io.aledep10.nomadSync.service.GitService} to avoid
 * duplication in test helpers and future utilities that need process execution
 * without depending on GitService directly.</p>
 *
 * <p>All methods are static — this class is not meant to be instantiated.</p>
 */
public final class CommandUtil {

    private CommandUtil() {
        // utility class — no instances
    }

    /**
     * Executes a command in the given directory and returns its exit code.
     * Output is streamed to the provided {@link LogService} if not {@code null}.
     *
     * @param directory  working directory for the command
     * @param command    command and arguments as a list of strings
     * @param logService logging service — may be {@code null} to suppress output
     * @return process exit code
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(File directory, List<String> command, LogService logService)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        pb.redirectErrorStream(true);

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
    }

    /**
     * Executes a command in the given directory and returns its exit code.
     * Output is suppressed. Convenience overload for test helpers.
     *
     * @param directory working directory for the command
     * @param command   command and arguments as a list of strings
     * @return process exit code
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static int runCommand(File directory, List<String> command)
            throws IOException, InterruptedException {
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
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static String runCommandWithOutput(File directory, List<String> command)
            throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        process.waitFor();
        return output.toString().trim();
    }
}