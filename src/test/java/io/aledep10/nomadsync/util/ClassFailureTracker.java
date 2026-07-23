package io.aledep10.nomadsync.util;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Tracks, across an entire test class, whether ANY test method failed or was
 * aborted — queried from {@code @AfterAll} to decide whether a class-shared
 * resource (typically a {@link TestVault} created once in {@code @BeforeAll})
 * should be cleaned up, or left on disk for inspection.
 *
 * <p>Distinct from {@link TempDirCleanupExtension}, which tracks pass/fail
 * per individual test method for per-test resources — this tracks the
 * aggregate outcome of the whole class, since a shared {@code @BeforeAll}
 * resource's lifecycle spans every test in the class, not just one.</p>
 *
 * <p>Also acts as a {@link ParameterResolver} for {@link ExtensionContext}
 * itself — JUnit5 does not resolve {@code ExtensionContext} as a parameter
 * on lifecycle methods by default (unlike {@code TestInfo}/{@code TestReporter}),
 * so a static {@code @AfterAll} method that wants to call
 * {@link #anyTestFailed(ExtensionContext)} needs this to declare
 * {@code ExtensionContext} as a parameter at all.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @ExtendWith(ClassFailureTracker.class)
 * class MyTest {
 *     @AfterAll
 *     static void tearDownAll(ExtensionContext context) throws IOException {
 *         logService.close();
 *         if (!ClassFailureTracker.anyTestFailed(context)) {
 *             TestUtil.cleanup(testVault);
 *         }
 *     }
 * }
 * }</pre>
 */
public class ClassFailureTracker implements TestWatcher, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ClassFailureTracker.class);
    private static final String FLAG_KEY = "anyTestFailed";

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        classStore(context).put(FLAG_KEY, Boolean.TRUE);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        classStore(context).put(FLAG_KEY, Boolean.TRUE);
    }

    /**
     * @param context the {@code @AfterAll} method's own {@link ExtensionContext}
     *                (already class-level — {@code @AfterAll} runs once per class)
     * @return {@code true} if any test in this class failed or was aborted
     */
    public static boolean anyTestFailed(ExtensionContext context) {
        Boolean flag = classStore(context).get(FLAG_KEY, Boolean.class);
        return flag != null && flag;
    }

    /**
     * Resolves the class-level store regardless of caller: {@code testFailed}/
     * {@code testAborted} receive a per-test-method context and must climb to
     * their parent; {@code @AfterAll} already receives the class-level context
     * directly.
     */
    private static ExtensionContext.Store classStore(ExtensionContext context) {
        ExtensionContext classContext = context.getTestMethod().isPresent()
                ? context.getParent().orElse(context)
                : context;
        return classContext.getStore(NAMESPACE);
    }

    // ── ParameterResolver — makes ExtensionContext itself injectable ───────────

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(ExtensionContext.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext;
    }
}