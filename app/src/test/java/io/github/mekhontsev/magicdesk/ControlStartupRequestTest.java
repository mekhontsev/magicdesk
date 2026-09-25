package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ControlStartupRequestTest {
    @Test public void failedPrerequisiteCheckOnlyRedirectsPendingRequest() throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mStartupAuditRunning = true, unavailable;
                Runnable mStartupRequest = () -> { throw new AssertionError("launch ran"); };
                final Profile mSessionProfile = new Profile();
                int redirects;
                boolean isActivityUnavailable() { return unavailable; }
                void startActivity(Intent intent) {
                    check(intent.profile == mSessionProfile, "selected startup profile lost");
                    redirects++;
                }
                static class Intent { Profile profile; }
                static class Profile {
                    void writeToIntent(Intent intent) { intent.profile = this; }
                }
                static class DeviceSetupActivity {
                    static Intent createLaunchIntent(Object context) { return new Intent(); }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.openDeviceSetupAfterFailedAudit();
                    check(!f.mStartupAuditRunning && f.mStartupRequest == null,
                            "failed startup retained its pending request");
                    check(f.redirects == 1, "setup was not shown");
                    f.unavailable = true;
                    f.openDeviceSetupAfterFailedAudit();
                    check(f.redirects == 1, "destroyed Activity opened setup");
                }
                """ + RuntimeSourceFixture.methods("ControlActivity", "openDeviceSetupAfterFailedAudit"));
    }

    @Test public void authorizationContinuesExactRequestOnlyOnce() throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mStartupPrepared;
                Runnable mStartupRequest;
                boolean unavailable;
                boolean isActivityUnavailable() { return unavailable; }
                static class DeviceSetupManager {
                    static int authorized;
                    static void authorizeRuntime(Object context) { authorized++; }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    int[] launches = {0};
                    f.mStartupRequest = () -> launches[0]++;
                    f.finishStartup();
                    check(launches[0] == 0, "request ran before successful audit");
                    f.mStartupPrepared = true;
                    f.finishStartup();
                    f.finishStartup();
                    check(launches[0] == 1 && DeviceSetupManager.authorized == 1,
                            "authorization lost or repeated the selected launch mode");
                    check(f.mStartupRequest == null, "completed request retained its Activity");
                    f.mStartupPrepared = true;
                    f.unavailable = true;
                    f.mStartupRequest = () -> launches[0]++;
                    f.finishStartup();
                    check(launches[0] == 1, "destroyed panel launched a workspace");
                }
                """ + RuntimeSourceFixture.methods("ControlActivity", "finishStartup"));
    }
}
