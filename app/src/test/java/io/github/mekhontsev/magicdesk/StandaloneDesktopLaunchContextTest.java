package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class StandaloneDesktopLaunchContextTest {
    @Test public void phoneAndExternalEntriesUseTheSameOrdinaryPlacementAndDelivery() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Intent {
                    static final int FLAG_ACTIVITY_NEW_TASK = 1;
                    void addFlags(int flags) { }
                }
                static class Context {
                    Object getPackageManager() { return null; }
                }
                enum Delivery { SHELL_INTENT, APP_PENDING_INTENT }
                static class AndroidLaunchSpec {
                    Delivery delivery;
                    Intent resolve(Object pm) { return new Intent(); }
                }
                static class InstancePolicy { Intent applyTo(Intent intent) { return intent; } }
                static class Presentation { InstancePolicy instancePolicy = new InstancePolicy(); }
                static class DesktopLaunchRequest {
                    AndroidLaunchSpec androidLaunch;
                    AndroidShortcutSpec androidShortcut;
                    Presentation presentation = new Presentation();
                }
                static class AndroidShortcutSpec {
                    Object application; Publisher publisher; String shortcutId;
                }
                static class Publisher { String packageName; }
                static class AppProfile { static void requireCurrent(Context a, Object p) { } }
                static class AndroidIntegrationGateway { static void requireShortcutPresentation(Presentation p) { } }
                static class DesktopActivityLaunchResult { interface Completion { void onComplete(Object result); } }
                static class TaskCommandQueue { static void execute(Runnable action) { action.run(); } }
                static class DesktopRuntimeBridge {
                    static Set<Integer> workspaceDisplayIds() { return Set.of(3); }
                }
                static class DesktopDisplayCatalog {
                    static void require(int id, String uniqueId) throws IOException {
                        if (!uniqueId.equals("display:" + id)) throw new IOException("stale display");
                    }
                }
                static class ShellAccess {
                    static Object getShortcutLaunchIntent(String p, String s) { return null; }
                    static void sendActivityOnDisplay(Object token, int d) { throw new AssertionError("not a shortcut"); }
                }
                static class OrdinaryActivityLaunch {
                    static int calls, display; static Delivery delivery;
                    static void requirePresentation(Presentation p) { }
                    static void launch(Context a, Intent i, Delivery d, int id) {
                        calls++; display = id; delivery = d;
                    }
                }
                static class FixtureContext {
                    final Context mContext = new Context();
                    void onMain(Runnable action) { action.run(); }
                    int mDisplayId; String mUniqueId;
                    boolean unavailable; int completions; Throwable failure;
                    boolean isUnavailable() { return unavailable; }
                    void launched(Runnable onPrepared, DesktopActivityLaunchResult.Completion completion) { completions++; }
                    void failed(DesktopLaunchRequest r, Throwable e, DesktopActivityLaunchResult.Completion c) { failure = e; }
                """ + RuntimeSourceFixture.methods("StandaloneDesktopLaunchContext", "launchAndroid") + """
                }
                public static void verify() {
                    for (int display : new int[]{0, 3}) {
                        for (Delivery delivery : Delivery.values()) {
                            var context = new FixtureContext();
                            context.mDisplayId = display; context.mUniqueId = "display:" + display;
                            var request = new DesktopLaunchRequest(); request.androidLaunch = new AndroidLaunchSpec();
                            request.androidLaunch.delivery = delivery;
                            int before = OrdinaryActivityLaunch.calls;
                            check(context.launchAndroid(request, null, null), "entry not accepted");
                            check(OrdinaryActivityLaunch.calls == before + 1 && OrdinaryActivityLaunch.display == display
                                    && OrdinaryActivityLaunch.delivery == delivery && context.completions == 1
                                    && context.failure == null, "entry bypassed placement or lost caller identity");
                            context.mUniqueId = "stale";
                            context.launchAndroid(request, null, null);
                            check(OrdinaryActivityLaunch.calls == before + 1 && context.failure != null,
                                    "stale destination dispatched an entry");
                        }
                    }
                }
                """, "ToolLaunchTarget");
    }
}
