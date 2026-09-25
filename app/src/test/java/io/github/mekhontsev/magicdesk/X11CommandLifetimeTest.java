package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class X11CommandLifetimeTest {
    @Test public void clientFailureCannotTerminateRetainedServer() throws Exception {
        RuntimeSourceFixture.verify("""
            static class Main { void post(Runnable task) { task.run(); } }
            static final Main MAIN = new Main();
            static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
            static class DesktopAutomationEventJournal { static void record(String type, String operation, boolean success, String detail) { } }
            boolean application, closed;
            String error = "";
            int changes;
            String id() { return "session"; }
            boolean stopped() { return closed; }
            void fail(Throwable failure) { closed = true; }
            void changed() { changes++; }
            public static void verify() {
                var retained = new Fixture();
                retained.commandFailed(new IOException("client disconnected"), true);
                check(!retained.closed && retained.error.equals("client disconnected") && retained.changes == 1,
                        "startup client failure killed retained server");
                retained.commandFailed(new IOException("later command failed"), false);
                check(!retained.closed && retained.changes == 2, "another client's failure killed retained server");
                retained.closed = true;
                retained.commandFailed(new IOException("late"), true);
                check(retained.changes == 2, "late result resurrected closed session");
                var application = new Fixture(); application.application = true;
                application.commandFailed(new IOException("launch failed"), true);
                check(application.closed, "application launch failure lost its cleanup");
            }
            """ + RuntimeSourceFixture.methods("X11Sessions", "commandFailed"));
    }
}
