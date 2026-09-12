package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopHomeDelegateRoutingTest {
    @Test
    public void primaryAndSecondaryHomeReuseTheRegisteredHost() throws Exception {
        verify("""
                for (String category : List.of(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME)) {
                    host = new DesktopShellActivity(1201, 17, null);
                    DesktopShellActivity delegate = new DesktopShellActivity(1206, 17,
                            new Intent(Intent.ACTION_MAIN, category));
                    check(gateway.desktopHomeRecipient(delegate) == host,
                            "HOME instance was not delegated: " + category);
                    check(gateway.desktopHomeRecipient(delegate) == host,
                            "repeated HOME request lost its recipient");
                    host.displayId = delegate.displayId = 0;
                    check(gateway.desktopHomeRecipient(delegate) == host,
                            "phone HOME routing changed");
                }
                """);
    }

    @Test
    public void homeNeverDelegatesToItselfOrAnotherDisplay() throws Exception {
        verify("""
                for (String category : List.of(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME)) {
                    host = new DesktopShellActivity(1201, 17, null);
                    DesktopShellActivity candidate = new DesktopShellActivity(1201, 17,
                            new Intent(Intent.ACTION_MAIN, category));
                    check(gateway.desktopHomeRecipient(candidate) == null, "self delegation");
                    candidate.taskId = 1206;
                    candidate.displayId = 0;
                    check(gateway.desktopHomeRecipient(candidate) == null, "cross-display delegation");
                    candidate.displayId = 17;
                    host = null;
                    check(gateway.desktopHomeRecipient(candidate) == null, "missing host accepted");
                }
                """);
    }

    @Test
    public void ordinaryLaunchesDoNotBecomeHomeDelegates() throws Exception {
        verify("""
                host = new DesktopShellActivity(1201, 17, null);
                for (Intent intent : Arrays.asList(null,
                        new Intent("VIEW", Intent.CATEGORY_HOME),
                        new Intent("VIEW", Intent.CATEGORY_SECONDARY_HOME),
                        new Intent(Intent.ACTION_MAIN, "LAUNCHER"),
                        new Intent(Intent.ACTION_MAIN, ""))) {
                    check(gateway.desktopHomeRecipient(new DesktopShellActivity(1206, 17, intent)) == null,
                            "ordinary launch became a delegate");
                }
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent {
                    static final String ACTION_MAIN = "MAIN", CATEGORY_HOME = "HOME",
                            CATEGORY_SECONDARY_HOME = "SECONDARY_HOME";
                    final String action, category;
                    Intent(String action, String category) { this.action = action; this.category = category; }
                    String getAction() { return action; }
                    boolean hasCategory(String value) { return value.equals(category); }
                }
                static class DesktopShellActivity {
                    int taskId, displayId;
                    final Intent intent;
                    DesktopShellActivity(int taskId, int displayId, Intent intent) {
                        this.taskId = taskId; this.displayId = displayId; this.intent = intent;
                    }
                    Intent getIntent() { return intent; }
                    int getTaskId() { return taskId; }
                    int getCurrentDisplayId() { return displayId; }
                }
                static DesktopShellActivity host;
                static class Gateway {
                    final Object mHostLock = new Object();
                    DesktopShellActivity reconcileSessionHostLocked(int displayId) { return host; }
                """ + RuntimeSourceFixture.methods("DesktopUiGateway", "desktopHomeRecipient")
                + "}\n" + """
                public static void verify() {
                    Gateway gateway = new Gateway();
                """ + scenario + "}\n");
    }
}
