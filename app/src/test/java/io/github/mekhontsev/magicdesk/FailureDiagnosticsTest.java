package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public final class FailureDiagnosticsTest {
    @Test public void binderMessageRetainsUnderlyingFrameworkFailure() {
        final Throwable original = new InvocationTargetException(
                new SecurityException("permission denied\nfor display 9"));
        final IllegalStateException sent = FailureDiagnostics.binderFailure("read display=9", original);
        final IllegalStateException received = new IllegalStateException(sent.getMessage());
        assertSame(original, sent.getCause());
        assertNull(received.getCause());
        assertTrue(received.getMessage().contains("java.lang.SecurityException: permission denied for display 9"));
        assertTrue(received.getMessage().contains("read display=9"));
    }

    @Test public void longOuterStackCannotHideDeepestCauseInJournal() {
        final Throwable root = new NoSuchMethodException("IWindowManager.getWindowingMode(int)");
        final Throwable outer = new IllegalStateException("wrapper " + "x".repeat(5000),
                new InvocationTargetException(root));
        final String detail = CompatibilityDiagnostics.formatFailure(outer);
        assertTrue(detail.startsWith("causes: java.lang.NoSuchMethodException: IWindowManager.getWindowingMode(int)"));
        assertTrue(detail.length() <= 2000);
        assertTrue(detail.endsWith("..."));
    }

    @Test public void cyclicAndDeepCausesAreBounded() {
        final Throwable first = new Exception("first");
        final Throwable second = new Exception("second", first);
        first.initCause(second);
        assertTrue(FailureDiagnostics.describe(first, 200).contains("cause chain incomplete"));
        Throwable chain = new Exception("root");
        for (int i = 0; i < 100; i++) chain = new Exception("level=" + i, chain);
        assertTrue(FailureDiagnostics.describe(chain, 200).contains("cause chain incomplete"));
        assertTrue(FailureDiagnostics.describe(chain, 200).length() <= 200);
        assertEquals("", FailureDiagnostics.describe(null, 200));
    }

    @Test public void limitsPreserveUnicodeAndStripControlSeparators() {
        assertEquals("a...", FailureDiagnostics.bounded("a\uD83D\uDE00zzz", 5));
        assertEquals("", FailureDiagnostics.bounded("value", 0));
        final String detail = FailureDiagnostics.describe(new Exception("one\r\ntwo\0end"), 200);
        assertFalse(detail.contains("\r"));
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\0"));
    }

    @Test public void displayReadAndWriteUseTheSameBinderFailureBoundary() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                record DisplayWindowingSnapshot(int displayId) { }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime displayWindowing() { return this; }
                    DisplayWindowingSnapshot read(int id) throws ReflectiveOperationException {
                        throw new InvocationTargetException(new SecurityException("read denied"));
                    }
                    void set(int id, String unique, int mode) throws ReflectiveOperationException {
                        throw new InvocationTargetException(new UnsupportedOperationException("write denied"));
                    }
                }
                """ + RuntimeSourceFixture.methods("ShellCommandService", "readDisplayWindowing", "setDisplayWindowing") + """
                public static void verify() {
                    Fixture service = new Fixture();
                    try { service.readDisplayWindowing(9); throw new AssertionError("read accepted"); }
                    catch (IllegalStateException error) {
                        check(error.getMessage().contains("display=9"), error.getMessage());
                        check(error.getMessage().contains("SecurityException: read denied"), error.getMessage());
                    }
                    try { service.setDisplayWindowing(9, "exact-display", 5); throw new AssertionError("write accepted"); }
                    catch (IllegalStateException error) {
                        check(error.getMessage().contains("display=9 mode=5"), error.getMessage());
                        check(error.getMessage().contains("UnsupportedOperationException: write denied"), error.getMessage());
                    }
                }
                """, "FailureDiagnostics", "BoundedText");
    }
}
