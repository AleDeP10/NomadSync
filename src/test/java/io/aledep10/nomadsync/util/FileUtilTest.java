package io.aledep10.nomadsync.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileUtil}.
 *
 * <p>Each test class uses an isolated temporary directory created in {@link #setUp}
 * and deleted in {@link #tearDown} — no shared mutable state between tests.</p>
 */
@DisplayName("Unit tests for FileUtil")
class FileUtilTest {

    private Path tempRoot;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("FileUtilTest");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(tempRoot)) {
            FileUtil.deleteRecursively(tempRoot);
        }
    }

    // ── deleteRecursively ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteRecursively")
    class DeleteRecursivelyTests {

        @Test
        @DisplayName("deletes a single empty directory")
        void emptyDirectory_isDeleted() throws IOException {
            Path dir = Files.createDirectory(tempRoot.resolve("empty"));

            FileUtil.deleteRecursively(dir);

            assertThat(dir).doesNotExist();
        }

        @Test
        @DisplayName("deletes a directory tree with files and subdirectories")
        void nestedTree_isDeletedCompletely() throws IOException {
            Path sub  = Files.createDirectory(tempRoot.resolve("sub"));
            Path deep = Files.createDirectory(sub.resolve("deep"));
            Files.writeString(sub.resolve("file.txt"), "content");
            Files.writeString(deep.resolve("nested.txt"), "content");

            FileUtil.deleteRecursively(tempRoot.resolve("sub"));

            assertThat(sub).doesNotExist();
        }

        @Test
        @DisplayName("is a no-op when the path does not exist")
        void nonExistentPath_isNoOp() throws IOException {
            Path ghost = tempRoot.resolve("nonexistent");

            FileUtil.deleteRecursively(ghost);

            assertThat(ghost).doesNotExist();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when root is null")
        void nullRoot_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> FileUtil.deleteRecursively(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── copyRecursively ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("copyRecursively")
    class CopyRecursivelyTests {

        @Test
        @DisplayName("copies files preserving relative structure")
        void tree_isCopiedWithStructure() throws IOException {
            Path source = Files.createDirectory(tempRoot.resolve("source"));
            Path sub    = Files.createDirectory(source.resolve("sub"));
            Files.writeString(source.resolve("root.txt"), "root");
            Files.writeString(sub.resolve("child.txt"), "child");

            Path target = tempRoot.resolve("target");
            FileUtil.copyRecursively(source, target);

            assertThat(target.resolve("root.txt")).exists();
            assertThat(target.resolve("sub").resolve("child.txt")).exists();
            assertThat(Files.readString(target.resolve("root.txt"))).isEqualTo("root");
            assertThat(Files.readString(target.resolve("sub").resolve("child.txt")))
                    .isEqualTo("child");
        }

        @Test
        @DisplayName("overwrites existing files at destination")
        void existingFile_isOverwritten() throws IOException {
            Path source = Files.createDirectory(tempRoot.resolve("source"));
            Path target = Files.createDirectory(tempRoot.resolve("target"));
            Files.writeString(source.resolve("file.txt"), "new content");
            Files.writeString(target.resolve("file.txt"), "old content");

            FileUtil.copyRecursively(source, target);

            assertThat(Files.readString(target.resolve("file.txt"))).isEqualTo("new content");
        }

        @Test
        @DisplayName("creates target directory if absent")
        void missingTarget_isCreated() throws IOException {
            Path source = Files.createDirectory(tempRoot.resolve("source"));
            Files.writeString(source.resolve("file.txt"), "content");
            Path target = tempRoot.resolve("new-target");

            FileUtil.copyRecursively(source, target);

            assertThat(target).isDirectory();
            assertThat(target.resolve("file.txt")).exists();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when source is null")
        void nullSource_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> FileUtil.copyRecursively(null, tempRoot))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when target is null")
        void nullTarget_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> FileUtil.copyRecursively(tempRoot, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── sizeOf ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sizeOf")
    class SizeOfTests {

        @Test
        @DisplayName("returns 0 for an empty directory")
        void emptyDirectory_returnsZero() throws IOException {
            assertThat(FileUtil.sizeOf(tempRoot)).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns 0 when directory does not exist")
        void nonExistentDirectory_returnsZero() throws IOException {
            assertThat(FileUtil.sizeOf(tempRoot.resolve("ghost"))).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns total size of all files recursively")
        void filesPresent_returnsTotalSize() throws IOException {
            Files.writeString(tempRoot.resolve("a.txt"), "hello");   // 5 bytes
            Path sub = Files.createDirectory(tempRoot.resolve("sub"));
            Files.writeString(sub.resolve("b.txt"), "world!"); // 6 bytes

            assertThat(FileUtil.sizeOf(tempRoot)).isEqualTo(11L);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when root is null")
        void nullRoot_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> FileUtil.sizeOf(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── listSorted ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listSorted")
    class ListSortedTests {

        @Test
        @DisplayName("returns empty list for empty directory")
        void emptyDirectory_returnsEmptyList() throws IOException {
            assertThat(FileUtil.listSorted(tempRoot)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when directory does not exist")
        void nonExistentDirectory_returnsEmptyList() throws IOException {
            assertThat(FileUtil.listSorted(tempRoot.resolve("ghost"))).isEmpty();
        }

        @Test
        @DisplayName("lists directories before files, both sorted alphabetically")
        void mixed_directoriesFirst() throws IOException {
            Files.writeString(tempRoot.resolve("z.txt"), "");
            Files.writeString(tempRoot.resolve("a.txt"), "");
            Files.createDirectory(tempRoot.resolve("beta"));
            Files.createDirectory(tempRoot.resolve("alpha"));

            List<Path> result = FileUtil.listSorted(tempRoot);

            assertThat(result).hasSize(4);
            assertThat(result.get(0).getFileName().toString()).isEqualTo("alpha");
            assertThat(result.get(1).getFileName().toString()).isEqualTo("beta");
            assertThat(result.get(2).getFileName().toString()).isEqualTo("a.txt");
            assertThat(result.get(3).getFileName().toString()).isEqualTo("z.txt");
        }

        @Test
        @DisplayName("returns only immediate children, not nested entries")
        void nested_onlyImmediateChildrenReturned() throws IOException {
            Path sub = Files.createDirectory(tempRoot.resolve("sub"));
            Files.writeString(sub.resolve("hidden.txt"), "");
            Files.writeString(tempRoot.resolve("visible.txt"), "");

            List<Path> result = FileUtil.listSorted(tempRoot);

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(p -> p.getFileName().toString()))
                    .containsExactly("sub", "visible.txt");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when directory is null")
        void nullDirectory_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> FileUtil.listSorted(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
