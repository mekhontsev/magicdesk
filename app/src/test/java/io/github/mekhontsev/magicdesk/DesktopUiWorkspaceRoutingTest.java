package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopUiWorkspaceRoutingTest {
    @Test
    public void queuedActionDoesNotFollowReplacedHostOrClosedWorkspace() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopShellActivity {
                    int displayId = 7;
                    boolean finishing, destroyed;
                    Runnable queued;
                    int getCurrentDisplayId() { return displayId; }
                    boolean isFinishing() { return finishing; }
                    boolean isDestroyed() { return destroyed; }
                    void runOnUiThread(Runnable action) { queued = action; }
                }
                static class DesktopWorkspaceRuntime {
                    DesktopShellActivity host;
                    DesktopShellActivity host() { return host; }
                }
                static class Registry {
                    DesktopWorkspaceRuntime current;
                    DesktopWorkspaceRuntime workspace(int displayId) {
                        return current != null && current.host.displayId == displayId
                                ? current : null;
                    }
                }
                static class Gateway {
                    final Object mHostLock = new Object();
                    final Registry mSession = new Registry();
                """ + RuntimeSourceFixture.methods("DesktopUiGateway",
                        "postToHost", "isCurrentHost", "isUsable") + "}\n" + """
                public static void verify() {
                    Gateway gateway = new Gateway();
                    DesktopShellActivity first = new DesktopShellActivity();
                    DesktopWorkspaceRuntime workspace = new DesktopWorkspaceRuntime();
                    workspace.host = first;
                    gateway.mSession.current = workspace;
                    int[] calls = {0};
                    gateway.postToHost(first, () -> calls[0]++);
                    first.queued.run();
                    check(calls[0] == 1, "current host lost its UI action");

                    gateway.postToHost(first, () -> calls[0]++);
                    workspace.host = new DesktopShellActivity();
                    first.queued.run();
                    check(calls[0] == 1, "old host callback survived recreation");

                    DesktopShellActivity replacement = workspace.host;
                    gateway.postToHost(replacement, () -> calls[0]++);
                    gateway.mSession.current = null;
                    replacement.queued.run();
                    check(calls[0] == 1, "closed workspace ran a UI action");

                    workspace.host = first;
                    gateway.mSession.current = workspace;
                    first.finishing = true;
                    check(!gateway.isCurrentHost(first), "finishing host accepted");
                    first.finishing = false;
                    first.destroyed = true;
                    check(!gateway.isCurrentHost(first), "destroyed host accepted");
                }
                """);
    }
}
