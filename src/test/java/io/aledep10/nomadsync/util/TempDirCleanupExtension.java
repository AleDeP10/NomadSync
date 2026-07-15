package io.aledep10.nomadsync.util;

import org.junit.jupiter.api.extension.*;

/**
 * JUnit5 extension providing {@link TempDirs} injection and conditional
 * cleanup: a test's registered temp directories are deleted <strong>only if
 * the test passed</strong>. A failed or aborted test's directories are left
 * on disk — the exact filesystem state at the moment of failure, ready for
 * inspection — instead of being silently erased by an unconditional
 * {@code @AfterEach}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @ExtendWith(TempDirCleanupExtension.class)
 * class MyTest {
 *     @Test
 *     void myScenario(TempDirs tempDirs) throws Exception {
 *         Path dir = tempDirs.newDir("MyTest", "scenario");
 *         // ... use dir; no manual cleanup needed ...
 *     }
 * }
 * }</pre>
 *
 * <p>Scoped to a single test invocation — each {@code @Test} method gets its
 * own {@link TempDirs} instance, isolated by JUnit5's own per-test
 * {@link ExtensionContext} hierarchy. Not intended for a class's shared,
 * {@code @BeforeAll}-created {@link TestVault} — that has its own,
 * unconditional lifecycle, since it is used by every test in the class,
 * passing or not.</p>
 */
public class TempDirCleanupExtension implements TestWatcher, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TempDirCleanupExtension.class);
    private static final String STORE_KEY = "tempDirs";

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(TempDirs.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE)
                .getOrComputeIfAbsent(STORE_KEY, key -> new TempDirs(), TempDirs.class);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        TempDirs tracked = context.getStore(NAMESPACE).get(STORE_KEY, TempDirs.class);
        if (tracked != null) {
            tracked.deleteAll();
        }
    }

    // testFailed(context, cause) / testAborted(context, cause): intentionally
    // left as TestWatcher's no-op default — the whole point is to leave the
    // directories intact for inspection.
}