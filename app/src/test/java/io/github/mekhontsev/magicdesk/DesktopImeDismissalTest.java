package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class DesktopImeDismissalTest {
    @Test
    public void desktopClickOnlyRequestsDismissalAndReportsUnavailableService() throws Exception {
        RuntimeSourceFixture.verify("""
                static int requested = -1, calls, failures;
                static boolean ready = true;
                static class ShellAccess {
                    static boolean requestHideCurrentInputMethod(int displayId) {
                        requested = displayId; calls++; return ready;
                    }
                }
                static class Log {
                    static void w(String tag, String detail) { failures++; }
                }
                static class Activity {
                    int displayId;
                    int getCurrentDisplayId() { return displayId; }
                }
                final Activity mActivity = new Activity();
                """ + RuntimeSourceFixture.methods("DesktopInputController", "onDesktopClick") + """
                public static void verify() {
                    Fixture input = new Fixture();
                    for (int display : new int[]{0, 70}) {
                        input.mActivity.displayId = display;
                        input.onDesktopClick();
                        check(requested == display, "lost originating display");
                    }
                    check(calls == 2 && failures == 0, "each click requests dismissal once");
                    ready = false;
                    input.onDesktopClick();
                    check(calls == 3 && failures == 1, "failure must be reported without retry");
                }
                """);
    }

    @Test
    public void shellUsesItsOwnIdentityAndContainsOptionalApiFailures() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static boolean cleared;
                static int calls, failures, fault;
                static class Binder {
                    static long clearCallingIdentity() { cleared = true; return 17; }
                    static void restoreCallingIdentity(long token) {
                        check(token == 17 && cleared, "wrong identity restored"); cleared = false;
                    }
                }
                static class Log {
                    static void w(String tag, String detail, Throwable error) { failures++; }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime inputMethod() throws ReflectiveOperationException {
                        if (fault == 1) throw new NoSuchMethodException("unavailable");
                        return this;
                    }
                    void hideCurrentInputMethod(int displayId) {
                        check(cleared, "must not forward application Binder identity");
                        check(displayId == 70, "originating display changed");
                        if (fault == 2) throw new SecurityException("denied");
                        calls++;
                    }
                }
                """ + RuntimeSourceFixture.methods("ShellCommandService", "requestHideCurrentInputMethod") + """
                public static void verify() {
                    Fixture service = new Fixture();
                    for (fault = 0; fault < 3; fault++) {
                        service.requestHideCurrentInputMethod(70);
                        check(!cleared, "identity leaked on success or failure");
                    }
                    check(calls == 1 && failures == 2, "optional failures not contained and logged");
                }
                """);
    }

    @Test
    public void wallpaperUsesCompletedClickWithoutChangingGestureOrFocusPolicy() throws Exception {
        final String content = RuntimeSourceFixture.methods("DesktopShellActivity", "createDesktopContentView");
        assertTrue(content.contains("desktop.setOnClickListener"));
        assertTrue(content.contains("mDesktopWorkspaceController.clearFileSelection()"));
        assertTrue(content.contains("mInputController.onDesktopClick()"));
        final String grid = RuntimeSourceFixture.methods("DesktopWorkspaceController", "createGrid");
        assertFalse(grid.contains("setOnClickListener"));
        assertFalse(grid.contains("setOnTouchListener"));
        assertTrue(content.contains("desktop.performClick()"));
        assertTrue(content.contains("desktop.setFocusable(false)"));
        assertTrue(content.contains("desktop.setFocusableInTouchMode(false)"));
        assertFalse(RuntimeSourceFixture.methods("DesktopShellActivity", "onDown", "onLongPress")
                .contains("onDesktopClick"));
        assertFalse(RuntimeSourceFixture.methods("DesktopInputController", "completeContextButtonClick")
                .contains("onDesktopClick"));
        final String aidl = Files.readString(Path.of(
                "src/main/aidl/io/github/mekhontsev/magicdesk/IShellCommandService.aidl"));
        assertTrue(aidl.contains("oneway void requestHideCurrentInputMethod(int originatingDisplayId)"));
        final String framework = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "FrameworkRuntime.java"));
        assertFalse(framework.contains("private final FrameworkInputMethodApi"));
    }
}
