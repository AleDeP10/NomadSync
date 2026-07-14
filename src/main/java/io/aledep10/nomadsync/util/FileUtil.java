package io.aledep10.nomadsync.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility class for filesystem operations not covered by {@link Files}.
 *
 * <p>All methods are static — this class is not meant to be instantiated.</p>
 *
 * <p>Methods that accept a {@link Path} validate that the argument is
 * non-null before proceeding. {@link IOException} is propagated to the
 * caller without wrapping — consistent with the {@link Files} API contract.</p>
 */
public final class FileUtil {

    private FileUtil() {}

    /**
     * Recursively deletes a directory and all its contents.
     *
     * <p>Files and subdirectories are deleted in reverse depth-first order —
     * each entry is deleted before its parent, satisfying the OS requirement
     * that a directory must be empty before it can be removed.</p>
     *
     * <p>If {@code root} does not exist, this method is a no-op.</p>
     *
     * @param root the directory to delete recursively
     * @throws IOException              if any entry cannot be deleted
     * @throws IllegalArgumentException if {@code root} is {@code null}
     */
    @SuppressWarnings("ResultOfMethodCallIgnored") // best-effort before delete — result intentionally discarded
    public static void deleteRecursively(Path root) throws IOException {
        ValidationUtil.requireNonNull(root, "root");
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            path.toFile().setWritable(true);
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    /**
     * Copies a directory tree from {@code source} to {@code target},
     * preserving the relative structure.
     *
     * <p>If {@code target} does not exist it is created. Existing files
     * at the destination are overwritten ({@link StandardCopyOption#REPLACE_EXISTING}).</p>
     *
     * @param source the directory to copy from
     * @param target the directory to copy into
     * @throws IOException              if any entry cannot be copied
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public static void copyRecursively(Path source, Path target) throws IOException {
        ValidationUtil.requireNonNull(source, "source");
        ValidationUtil.requireNonNull(target, "target");
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                Path relative    = source.relativize(entry);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Returns the total size in bytes of all files under {@code root},
     * recursively.
     *
     * <p>Symbolic links are not followed.</p>
     *
     * @param root the directory to measure
     * @return total size in bytes; {@code 0} if the directory is empty or
     *         does not exist
     * @throws IOException              if the directory cannot be walked
     * @throws IllegalArgumentException if {@code root} is {@code null}
     */
    public static long sizeOf(Path root) throws IOException {
        ValidationUtil.requireNonNull(root, "root");
        if (!Files.exists(root)) return 0L;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }

    /**
     * Lists the immediate children of {@code directory} sorted alphabetically,
     * directories first.
     *
     * @param directory the directory to list
     * @return sorted list of immediate children; empty if the directory is
     *         empty or does not exist
     * @throws IOException              if the directory cannot be listed
     * @throws IllegalArgumentException if {@code directory} is {@code null}
     */
    public static List<Path> listSorted(Path directory) throws IOException {
        ValidationUtil.requireNonNull(directory, "directory");
        if (!Files.exists(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(Path::getFileName))
                    .toList();
        }
    }
}