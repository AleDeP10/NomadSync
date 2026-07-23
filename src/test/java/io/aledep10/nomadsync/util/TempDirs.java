package io.aledep10.nomadsync.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-test-invocation accumulator of temporary directories and secondary
 * {@link TestVault} instances — injected as a parameter into any
 * {@code @Test} (or lifecycle) method via {@link TempDirCleanupExtension}.
 *
 * <p>Everything registered here is deleted automatically once the test
 * completes — but only if it <strong>passed</strong>; see
 * {@link TempDirCleanupExtension} for the pass/fail distinction. Never
 * instantiate directly — obtained exclusively via parameter injection.</p>
 */
public class TempDirs {

    private final List<Path> dirs = new ArrayList<>();
    private final List<TestVault> vaults = new ArrayList<>();

    /**
     * Creates a new temp directory under the shared NomadSync test tree (via
     * {@link TestUtil#testTempDir}) and registers it for conditional cleanup.
     *
     * @param testClassName the requesting test class — used as the bucket
     *                      subdirectory, matching {@link TestUtil#getTestVault}'s
     *                      own convention
     * @param prefix        short, descriptive prefix for the leaf directory name
     * @return the newly created, guaranteed-unique directory
     * @throws IOException if the directory cannot be created
     */
    public Path newDir(String testClassName, String prefix) throws IOException {
        Path dir = TestUtil.testTempDir(testClassName, prefix);
        dirs.add(dir);
        return dir;
    }

    /**
     * Registers an already-existing directory for conditional cleanup, without
     * creating it — for directories a test must create at a specific location
     * outside the shared {@code NomadSync_tests} tree (e.g. a CWD-relative path,
     * used to exercise real relative-path resolution rather than mocking it away).
     *
     * @param existingDir a directory the caller has already created
     * @return {@code existingDir}, unchanged, for convenient chaining
     */
    public Path registerDir(Path existingDir) {
        dirs.add(existingDir);
        return existingDir;
    }

    /**
     * Creates a new secondary {@link TestVault} (via {@link TestUtil#getTestVault})
     * and registers it for conditional cleanup — for tests that need more than
     * one isolated vault environment (e.g. cross-vault isolation checks, or a
     * per-test vault environment distinct from a class-shared one used only
     * for logging).
     *
     * @param testName identifier passed to {@link TestUtil#getTestVault}
     * @return the newly created {@link TestVault}
     * @throws IOException if the vault's directories cannot be created
     */
    public TestVault newVault(String testName) throws IOException {
        TestVault vault = TestUtil.getTestVault(testName);
        vaults.add(vault);
        return vault;
    }

    /**
     * Deletes every registered directory and {@link TestVault} — called only
     * by {@link TempDirCleanupExtension#testSuccessful}, never directly by
     * test code.
     */
    void deleteAll() {
        for (Path dir : dirs) {
            try {
                FileUtil.deleteRecursively(dir);
            } catch (IOException e) {
                System.err.println("[TempDirs] unable to delete " + dir + ": " + e.getMessage());
            }
        }
        dirs.clear();

        for (TestVault vault : vaults) {
            try {
                TestUtil.cleanup(vault);
            } catch (IOException e) {
                System.err.println("[TempDirs] unable to clean up vault " + vault.rootPath()
                        + ": " + e.getMessage());
            }
        }
        vaults.clear();
    }
}