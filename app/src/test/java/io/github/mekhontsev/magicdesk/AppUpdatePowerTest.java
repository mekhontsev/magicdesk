package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppUpdatePowerTest {
    private static final String BUNDLE = """
            static class Bundle {
                Map<String,Object> values = new HashMap<>();
                void putString(String k, String v) { values.put(k, v); }
                void putLong(String k, long v) { values.put(k, v); }
                void putBoolean(String k, boolean v) { values.put(k, v); }
                String getString(String k, String fallback) { return (String) values.getOrDefault(k, fallback); }
                long getLong(String k) { return (Long) values.getOrDefault(k, 0L); }
                boolean getBoolean(String k) { return (Boolean) values.getOrDefault(k, false); }
            }
            """;

    @Test public void snapshotIsBoundedAndDoesNotExtendTheLease() throws Exception {
        RuntimeSourceFixture.verify(BUNDLE
                + RuntimeSourceFixture.nestedClass("AppUpdatePowerState", "AppUpdatePowerState") + """
                static void rejects(Runnable r) {
                    try { r.run(); throw new AssertionError("accepted invalid state"); }
                    catch (IllegalArgumentException expected) { }
                }
                public static void verify() {
                    var saved = new AppUpdatePowerState("owner", 3000, false);
                    check(saved.remaining(1000) == 2000, "remaining time");
                    check(saved.bridgeDuration(1000, 1000) == 1000, "worker bound");
                    check(saved.bridgeDuration(2500, 1000) == 500, "lease bound");
                    check(saved.bridgeDuration(3000, 1000) == 0, "expired bridge");
                    check(AppUpdatePowerState.fromBundle(saved.toBundle(), 1500).equals(saved), "round trip");
                    check(new AppUpdatePowerState("", 0, true).bridgeDuration(1000, 5000) == 5000, "black-only bridge");
                    check(!new AppUpdatePowerState("", 0, false).requested(), "off state requested restoration");
                    rejects(() -> new AppUpdatePowerState(null, 0, false));
                    rejects(() -> new AppUpdatePowerState("owner", 0, false));
                    rejects(() -> new AppUpdatePowerState("", 1000, false));
                    rejects(() -> AppUpdatePowerState.fromBundle(null, 0));
                    rejects(() -> AppUpdatePowerState.fromBundle(new AppUpdatePowerState("owner", 1_800_001, false).toBundle(), 0));
                }
                """);
    }

    @Test public void resumeIsOneShotAndBelongsToTheUpdatingApp() throws Exception {
        RuntimeSourceFixture.verify(BUNDLE
                + RuntimeSourceFixture.nestedClass("AppUpdatePowerState", "AppUpdatePowerState") + """
                static class Binder { static int caller = 10001; static int getCallingUid() { return caller; } }
                static class SystemClock { static long now = 100; static long uptimeMillis() { return now; } }
                Object mLock = new Object();
                int mAppUid = 10001;
                String mUpdateId = "update", mResumeError;
                long mResumeDeadline;
                boolean mClosed, mClaimed, mResumed;
                AppUpdatePowerState mPower = new AppUpdatePowerState("lease", 5000, true);
                static void rejects(Runnable r) {
                    try { r.run(); throw new AssertionError("accepted stale/foreign resume"); }
                    catch (IllegalStateException | SecurityException expected) { }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    rejects(() -> f.claimPowerState("update"));
                    f.mResumeDeadline = 1000;
                    rejects(() -> f.completeResume("update", ""));
                    rejects(() -> f.claimPowerState("another"));
                    Binder.caller = 10002;
                    rejects(() -> f.claimPowerState("update"));
                    Binder.caller = 10001;
                    var state = f.claimPowerState("update");
                    check(state.getLong("resumeDeadline") == 1000 && state.getBoolean("blackScreen"), "lost resume context");
                    rejects(() -> f.claimPowerState("update"));
                    f.completeResume("update", "");
                    check(f.mResumed && f.mResumeError.isEmpty(), "no acknowledgement");
                    rejects(() -> f.completeResume("update", ""));
                    f.mResumed = false;
                    SystemClock.now = 1000;
                    rejects(() -> f.completeResume("update", ""));
                    SystemClock.now = 100;
                    f.mClosed = true;
                    rejects(() -> f.completeResume("update", ""));
                    f.mClosed = false;
                    f.completeResume("update", "x".repeat(2000));
                    check(f.mResumeError.length() == 1024, "unbounded receipt detail");
                }
                """ + RuntimeSourceFixture.methods("ShellAppUpdateService", "claimPowerState", "completeResume", "requireResume"));
    }

    @Test public void workerBridgesReplacementAndAlwaysReleases() throws Exception {
        String run = RuntimeSourceFixture.methods("ShellAppUpdateService", "run");
        assertTrue(run.indexOf("acquirePowerBridge()") < run.indexOf("ShellAppUpdate.commit("));
        assertTrue(run.contains("EventDrivenWaits.Reason.APP_UPDATE_RESUME"));
        String cleanup = RuntimeSourceFixture.finallyBlock("ShellAppUpdateService", "run");
        assertTrue(cleanup.contains("mPowerBridge.release()"));
        assertTrue(cleanup.indexOf("mPowerBridge.release()") < cleanup.indexOf("System.exit(0)"));
        String power = RuntimeSourceFixture.methods("ShellAppUpdateService", "acquirePowerBridge");
        assertFalse(power.contains("ACQUIRE_CAUSES_WAKEUP"));
        assertTrue(power.contains("mPowerBridge.acquire(duration)"));
        String restore = RuntimeSourceFixture.methods("AppUpdateResumeActivity", "restorePower");
        assertTrue(restore.contains("restored.isDone()"));
        assertTrue(restore.indexOf("restoreUpdatePower(power)") < restore.indexOf("restored.complete(true)"));
        assertFalse(restore.contains("SharedPreferences"));
    }
}
