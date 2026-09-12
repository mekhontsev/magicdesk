package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellServiceConnectionLifecycleTest {
    @Test public void exitBlocksRebindingUntilExplicitRuntimeStart() throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object mLock = new Object();
                static boolean mEnabled = true;
                static Object mService;
                static int mUid = -1;
                static Attempt mAttempt;
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.toString(); } }
                static class Attempt {
                    boolean finished;
                    String error;
                    ShellServiceLauncher.Binding owner;
                    Attempt(long timeout) {}
                    void close() { if (owner != null) owner.close(true); }
                }
                static class ShellServiceLauncher {
                    enum Service { COMMAND }
                    interface Binding { void close(boolean terminate); }
                    static final ShellServiceLauncher INSTANCE = new ShellServiceLauncher();
                    static int binds, closes;
                    static boolean cancelDuringBind;
                    static ShellServiceLauncher current() { return INSTANCE; }
                    boolean canBind() { return true; }
                    long bindTimeoutMillis() { return 1000; }
                    Binding bind(Service service, String name, Attempt attempt) {
                        binds++;
                        if (cancelDuringBind) disconnect();
                        return terminate -> { closes++; connect(); };
                    }
                }
                """ + RuntimeSourceFixture.methods("ShellServiceConnection", "connect", "disconnect", "clear", "resume")
                    .replace("void connect()", "static void connect()")
                    .replace("void disconnect()", "static void disconnect()")
                    .replace("void clear()", "static void clear()")
                    .replace("boolean resume()", "static boolean resume()") + """
                public static void verify() {
                    connect();
                    check(ShellServiceLauncher.binds == 1, "initial binding missing");
                    disconnect();
                    connect();
                    check(ShellServiceLauncher.binds == 1 && ShellServiceLauncher.closes == 1,
                            "disconnect rebound through a late launcher callback");
                    check(resume(), "explicit reopening did not resume binding");
                    connect();
                    check(!resume() && ShellServiceLauncher.binds == 2, "ordinary tool opening restarted binding");
                    disconnect();
                    ShellServiceLauncher.cancelDuringBind = true;
                    resume();
                    connect();
                    check(mAttempt == null && ShellServiceLauncher.binds == 3 && ShellServiceLauncher.closes == 3,
                            "cancelled binding retained a late owner");
                }
                """);
    }
}
