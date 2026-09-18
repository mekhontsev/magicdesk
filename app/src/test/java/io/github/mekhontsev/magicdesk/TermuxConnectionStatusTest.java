package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

public class TermuxConnectionStatusTest {
    private static final TermuxIntegration.Endpoint READY = endpoint("com.termux", true, true, false, "");
    private static final TermuxIntegration.CommandResult OK = new TermuxIntegration.CommandResult(
            0, -1, "magicdesk-termux-ok\n", "", "");

    private static final class Probe implements TermuxConnectionStatus.Probe {
        final List<TermuxIntegration.ResultCallback> calls = new ArrayList<>();
        int cancelled;

        @Override public Runnable start(android.content.Context context, TermuxIntegration.Endpoint endpoint,
                TermuxIntegration.ResultCallback callback) {
            calls.add(callback);
            return () -> ++cancelled;
        }
    }

    @Test public void waitsForUiAndChecksOnceAcrossRepeatedResumes() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        assertEquals(TermuxConnectionStatus.State.UNCHECKED, status.current(READY).state());
        assertTrue(probe.calls.isEmpty());
        status.check(null, READY, false);
        assertEquals(TermuxConnectionStatus.State.CHECKING, status.current(READY).state());
        status.check(null, READY, false);
        status.check(null, READY, true);
        assertEquals(1, probe.calls.size());
        probe.calls.get(0).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.READY, status.current(READY).state());
        status.check(null, READY, false);
        assertEquals(1, probe.calls.size());
    }

    @Test public void missingOrDisabledPrerequisitesDoNotStartCommands() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        status.check(null, endpoint("com.termux", false, true, false, ""), false);
        status.check(null, endpoint("com.termux", true, false, false, "not installed"), false);
        status.check(null, endpoint("com.termux", true, true, false, "incompatible service"), true);
        status.check(null, endpoint("com.termux", true, true, true, "permission denied"), false);
        assertTrue(probe.calls.isEmpty());
        status.check(null, READY, false);
        assertEquals(1, probe.calls.size());
    }

    @Test public void timeoutRemainsUnknownAndNeedsExplicitRetry() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        status.check(null, READY, false);
        probe.calls.get(0).onResult(null, new TimeoutException());
        assertEquals(TermuxConnectionStatus.State.TIMED_OUT, status.current(READY).state());
        assertTrue(READY.available());
        status.check(null, READY, false);
        assertEquals(1, probe.calls.size());
        status.check(null, READY, true);
        assertEquals(2, probe.calls.size());
        probe.calls.get(0).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.CHECKING, status.current(READY).state());
        probe.calls.get(1).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.READY, status.current(READY).state());
    }

    @Test public void rejectedCommandRetainsErrorWithoutBlockingToolsOrAutomaticRetry() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        status.check(null, READY, false);
        probe.calls.get(0).onResult(new TermuxIntegration.CommandResult(1, 4, "", "", "external apps disabled"), null);
        assertEquals(TermuxConnectionStatus.State.FAILED, status.current(READY).state());
        assertEquals("external apps disabled", status.current(READY).error());
        assertTrue(READY.available());
        status.check(null, READY, false);
        assertEquals(1, probe.calls.size());
    }

    @Test public void synchronousServiceRefusalFinishesCheck() {
        final var status = new TermuxConnectionStatus((context, endpoint, callback) -> {
            throw new IllegalStateException("service refused");
        });
        status.check(null, READY, false);
        assertEquals(TermuxConnectionStatus.State.FAILED, status.current(READY).state());
        assertTrue(status.current(READY).error().contains("service refused"));
    }

    @Test public void changedEndpointCancelsAndRejectsOldReply() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        final var replacement = endpoint("org.example.termux", true, true, false, "");
        status.check(null, READY, false);
        assertEquals(TermuxConnectionStatus.State.UNCHECKED, status.current(replacement).state());
        status.check(null, replacement, false);
        assertEquals(1, probe.cancelled);
        probe.calls.get(0).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.CHECKING, status.current(replacement).state());
        probe.calls.get(1).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.READY, status.current(replacement).state());
        assertEquals(TermuxConnectionStatus.State.UNCHECKED, status.current(READY).state());
    }

    @Test public void permissionLossInvalidatesEvenAnInFlightProbe() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        final var denied = endpoint("com.termux", true, true, true, "permission denied");
        status.check(null, READY, false);
        status.check(null, denied, false);
        assertEquals(1, probe.cancelled);
        probe.calls.get(0).onResult(OK, null);
        assertEquals(TermuxConnectionStatus.State.UNCHECKED, status.current(denied).state());
        status.check(null, READY, false);
        assertEquals(2, probe.calls.size());
    }

    @Test public void closedUiUnsubscribesWithoutCancellingSharedResult() {
        final var probe = new Probe();
        final var status = new TermuxConnectionStatus(probe);
        final var updates = new ArrayList<TermuxConnectionStatus.State>();
        final Runnable listener = () -> updates.add(status.current(READY).state());
        status.addListener(listener);
        status.check(null, READY, false);
        status.removeListener(listener);
        probe.calls.get(0).onResult(OK, null);
        assertEquals(List.of(TermuxConnectionStatus.State.UNCHECKED, TermuxConnectionStatus.State.CHECKING), updates);
        assertEquals(0, probe.cancelled);
        status.addListener(listener);
        assertEquals(TermuxConnectionStatus.State.READY, updates.get(2));
    }

    @Test public void immediateReplyDoesNotLeaveAPendingCheck() {
        final int[] cancellations = {0};
        final var status = new TermuxConnectionStatus((context, endpoint, callback) -> {
            callback.onResult(OK, null);
            return () -> ++cancellations[0];
        });
        status.check(null, READY, false);
        assertEquals(TermuxConnectionStatus.State.READY, status.current(READY).state());
        assertEquals(1, cancellations[0]);
    }

    @Test public void startupOnlyRegistersUiCallbackAndDoesNotGateCapabilities() throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "TermuxConnectionStatus.java"));
        assertTrue(source.contains("onActivityPostResumed(Activity activity)"));
        assertEquals(1, source.split("INSTANCE.check\\(", -1).length - 1);
        assertFalse(source.contains("ShellAccess.isReady"));
        assertFalse(source.contains("DesktopRuntime"));
        assertFalse(source.contains("postDelayed"));
        final String startup = RuntimeSourceFixture.methods("MagicDeskApplication", "onCreate");
        assertTrue(startup.indexOf("!isPrimaryProcess") < startup.indexOf("TermuxConnectionStatus.initialize(this)"));
        assertFalse(Files.readString(Path.of(RuntimeSourceFixture.MAIN + "RuntimeCapabilities.java"))
                .contains("TermuxConnectionStatus"));
        final String panel = RuntimeSourceFixture.methods("ControlActivity", "refresh");
        assertFalse(panel.contains("TermuxConnectionStatus"));
    }

    @Test public void backgroundStartupDoesNotDispatchUntilALiveActivityResumes() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Bundle { }
                static class Activity {
                    boolean finishing, destroyed;
                    boolean isFinishing() { return finishing; }
                    boolean isDestroyed() { return destroyed; }
                }
                static class Application {
                    ActivityLifecycleCallbacks callbacks;
                    interface ActivityLifecycleCallbacks {
                        void onActivityPostResumed(Activity a);
                        void onActivityCreated(Activity a, Bundle b);
                        void onActivityStarted(Activity a);
                        void onActivityResumed(Activity a);
                        void onActivityPaused(Activity a);
                        void onActivityStopped(Activity a);
                        void onActivitySaveInstanceState(Activity a, Bundle b);
                        void onActivityDestroyed(Activity a);
                    }
                    void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { callbacks = c; }
                }
                static int inspections, checks;
                static class TermuxIntegration {
                    static String inspect(Application app) { ++inspections; return "endpoint"; }
                }
                static final Status INSTANCE = new Status();
                static class Status {
                    void check(Application app, String endpoint, boolean retry) {
                        Fixture.check(!retry && endpoint.equals("endpoint"), "automatic check must not force a retry");
                        ++checks;
                    }
                }
                """ + RuntimeSourceFixture.methods("TermuxConnectionStatus", "initialize") + """
                public static void verify() {
                    Application app = new Application();
                    initialize(app);
                    check(inspections == 0 && checks == 0, "background startup checked Termux");
                    Activity activity = new Activity();
                    app.callbacks.onActivityCreated(activity, null);
                    app.callbacks.onActivityStarted(activity);
                    check(checks == 0, "checked before UI resumed");
                    activity.finishing = true;
                    app.callbacks.onActivityPostResumed(activity);
                    activity.finishing = false;
                    activity.destroyed = true;
                    app.callbacks.onActivityPostResumed(activity);
                    check(checks == 0, "checked with a dead UI");
                    activity.destroyed = false;
                    app.callbacks.onActivityPostResumed(activity);
                    check(inspections == 1 && checks == 1, "live UI did not request a check");
                }
                """);
    }

    private static TermuxIntegration.Endpoint endpoint(String packageName, boolean enabled,
            boolean installed, boolean permission, String error) {
        return new TermuxIntegration.Endpoint(packageName, enabled, installed, null,
                "/termux/home", 12345, permission, error);
    }
}
