package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class OrdinaryActivityLaunchTest {
    @Test public void ordinaryPresentationDoesNotRequireDesktop() {
        for (final var mode : new DesktopLaunchMode[] {DesktopLaunchMode.AUTO, DesktopLaunchMode.FULLSCREEN}) {
            for (final var instance : DesktopTaskInstancePolicy.values()) {
                OrdinaryActivityLaunch.requirePresentation(
                        DesktopLaunchPresentation.forMode(mode).withInstancePolicy(instance));
            }
        }
    }

    @Test public void desktopOnlyParametersAreNotSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class, () -> OrdinaryActivityLaunch.requirePresentation(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED)));
        assertThrows(IllegalArgumentException.class, () -> OrdinaryActivityLaunch.requirePresentation(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN).withPreferredTask(12)));
    }

    @Test public void acceptanceDoesNotInventAnObservedTask() throws Exception {
        final var result = OrdinaryActivityLaunch.accepted(0,
                new JSONObject().put("requestId", "result-request").put("delivery", "pending-intent"));
        assertTrue(result.success);
        assertTrue(result.data.getBoolean("accepted"));
        assertFalse(result.data.getBoolean("taskObserved"));
        assertFalse(result.data.has("taskId"));
        assertFalse(result.data.has("reused"));
        assertEquals(0, result.data.getInt("displayId"));
        assertEquals("result-request", result.data.getString("requestId"));
        assertEquals("pending-intent", result.data.getString("delivery"));
        assertTrue(result.data.getString("nextAction").contains("ui.wait"));
    }

    @Test public void dispatchPreservesIdentityAndRevokesOnlyItsOwnToken() throws Exception {
        RuntimeSourceFixture.verify("""
                    static class Context {}
                    static class Intent {}
                    static class PendingIntent { int cancellations; void cancel() { cancellations++; } }
                    static class AndroidLaunchSpec { enum Delivery { SHELL_INTENT, APP_PENDING_INTENT } }
                    static class AndroidPendingActivityLaunch {
                        static PendingIntent token; static Intent source;
                        static PendingIntent create(Context c, Intent intent) {
                            source=intent; return token=new PendingIntent();
                        }
                    }
                    static class ShellAccess {
                        static Intent source; static PendingIntent token; static int display;
                        static boolean fullscreen; static boolean fail;
                        static void sendActivityOnDisplay(PendingIntent p, int d) throws IOException {
                            token=p; display=d;
                            if(fail) throw new IOException("rejected");
                        }
                        static void launchActivityOnDisplay(Intent i, int d, boolean f) {
                            source=i; display=d; fullscreen=f;
                        }
                    }
                """ + RuntimeSourceFixture.methods("OrdinaryActivityLaunch", "launch") + """
                    public static void verify() throws Exception {
                        Context c=new Context(); Intent i=new Intent();
                        launch(c,i,AndroidLaunchSpec.Delivery.SHELL_INTENT,0);
                        if(ShellAccess.source!=i || !ShellAccess.fullscreen || ShellAccess.display!=0
                                || AndroidPendingActivityLaunch.token!=null) throw new AssertionError("direct path");
                        launch(c,i,AndroidLaunchSpec.Delivery.APP_PENDING_INTENT,7);
                        if(AndroidPendingActivityLaunch.source!=i || ShellAccess.display!=7
                                || ShellAccess.token!=AndroidPendingActivityLaunch.token
                                || ShellAccess.token.cancellations!=1) throw new AssertionError("app identity");
                        ShellAccess.fail=true;
                        try { launch(c,i,AndroidLaunchSpec.Delivery.APP_PENDING_INTENT,0);
                            throw new AssertionError("failure was hidden");
                        } catch(IOException expected) {}
                        if(ShellAccess.token.cancellations!=1) throw new AssertionError("failed token leaked");
                    }
                """);
    }
}
