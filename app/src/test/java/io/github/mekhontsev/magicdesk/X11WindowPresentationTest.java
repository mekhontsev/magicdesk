package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class X11WindowPresentationTest {
    @Test public void presentationDoesNotRequireFocusAndDoesNotReopenClosedHosts() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("X11WindowPresentation", "X11WindowPresentation")
                .replace("WeakReference<", "java.lang.ref.WeakReference<") + """
            static class Context { }
            static class Activity extends Context {
                boolean destroyed, finishing;
                boolean isDestroyed() { return destroyed; }
                boolean isFinishing() { return finishing; }
                Display getDisplay() { return new Display(); }
                int getTaskId() { return 1; }
            }
            static class Display { int getDisplayId() { return 7; } }
            static class Looper { static Object getMainLooper() { return null; } }
            static class Handler {
                Handler(Object looper) { }
                void post(Runnable work) { work.run(); }
            }
            static class TaskCommandQueue { static void execute(Runnable work) { work.run(); } }
            static class ShellAccess { static boolean ready; static boolean isReady() { return ready; } }
            static class Intent { Intent putExtra(String key, Object value) { return this; } }
            static class X11Activity {
                static final String SESSION = "session", WINDOW = "window";
                static Intent createIntent(Context context) { return new Intent(); }
            }
            static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
            static class ToolApplications {
                record SiblingPlacement(int target, String uniqueId) { }
                static int opens;
                static boolean fail;
                static SiblingPlacement siblingPlacement(int display, int task) throws IOException {
                    return new SiblingPlacement(display, "stable-display");
                }
                static void openSibling(Activity activity, Intent intent, BuiltInWindowLauncher.Callback done) {
                    opens++; done.onComplete(fail ? new IOException("unavailable") : null);
                }
                static void open(Context context, Intent intent, int target, String identity, BuiltInWindowLauncher.Callback done) {
                    check(target == 7 && identity.equals("stable-display"), "captured placement and stable identity");
                    opens++; done.onComplete(null);
                }
            }
            static class X11Sessions {
                static class Session {
                    int changes, failures;
                    String id() { return "session"; }
                    void presentationChanged() { changes++; }
                    void presentationFailed(Throwable error) { failures++; }
                }
            }
            public static void verify() {
                var session = new X11Sessions.Session();
                var presentation = new X11WindowPresentation(new Context(), session);
                check(!presentation.present(1), "no host or placement yet");
                var host = new Activity();
                presentation.host(host);
                check(session.changes == 1, "placement availability wakes presentation");
                check(presentation.present(1), "live host, no focus prerequisite or shell");
                check(!presentation.present(1) && ToolApplications.opens == 1, "duplicate catalog delivery");
                presentation.claim(2);
                check(!presentation.present(2), "launch host already owns window");
                host.destroyed = true;
                check(!presentation.present(3), "no shell does not silently elevate background launch");
                ShellAccess.ready = true;
                check(presentation.present(3), "last verified destination survives destroyed host");
                check(!presentation.present(1), "WM_DELETE refusal does not reopen closed host");
                presentation.host(new Activity());
                ToolApplications.fail = true;
                check(presentation.present(4) && session.failures == 1, "surface launch error");
                check(!presentation.present(4), "no automatic failure loop");
                presentation.retain(Set.of(1L, 2L, 3L));
                ToolApplications.fail = false;
                check(presentation.present(4), "removed then recreated window can be shown");
                presentation.close();
                check(!presentation.present(5), "closed session ignores callbacks");
            }
            """);
    }
}
