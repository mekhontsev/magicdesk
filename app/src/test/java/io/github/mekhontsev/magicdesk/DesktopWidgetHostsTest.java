package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopWidgetHostsTest {
    @Test public void concurrentWorkspacesAndRecreatedHostsKeepIndependentCallbacks() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
            public static void verify() throws Exception {
                Host phoneHost = new Host(null, 1);
                phoneHost.ids = new int[]{10};
                Host externalHost = new Host(null, 2);
                externalHost.ids = new int[]{20};
                int[] changes = new int[3];
                Lease phone = acquire("phone", phoneHost, () -> changes[0]++);
                Lease external = acquire("external", externalHost, () -> changes[1]++);
                phone.start(); external.start(); phone.start();
                check(phoneHost.starts == 1 && externalHost.starts == 1, "idempotent separate listeners");
                check(phone.owns(10) && !phone.owns(20) && external.owns(20), "Android host IDs isolate widgets");
                phoneHost.emit(); externalHost.emit();
                check(changes[0] == 1 && changes[1] == 1, "both receive provider events");

                Host replacementHost = new Host(null, 1);
                replacementHost.ids = phoneHost.ids;
                Lease replacement = acquire("phone", replacementHost, () -> changes[2]++);
                replacement.start();
                check(phoneHost.stops == 1 && phoneHost.clears == 1, "retire old callback and Activity views");
                check(!phone.isCurrent() && !phone.owns(10), "old UI cannot mutate retained widget");
                phone.stop(); phone.release(); phone.start(); phoneHost.emit();
                replacementHost.emit(); externalHost.emit();
                check(changes[0] == 1 && changes[1] == 2 && changes[2] == 1, "late lifecycle cannot stop current host");
                check(replacementHost.stops == 0 && externalHost.stops == 0, "other listeners unchanged");
                replacement.release(); replacement.release();
                check(replacementHost.stops == 1 && replacementHost.clears == 1, "release is idempotent");
                check(replacementHost.ids.length == 1, "Close preserves Android bindings");
                check(ACTIVE.size() == 1 && external.isCurrent(), "other workspace retained");
                external.stop(); external.start(); externalHost.emit();
                check(changes[1] == 3, "restart continues delivery");
                external.release();
                check(ACTIVE.isEmpty(), "all Activity owners released");
            }
            """);
    }

    @Test public void failedStartAndStopCannotLeakAnActivityOrDeleteBindings() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
            public static void verify() throws Exception {
                Host host = new Host(null, 1); host.ids = new int[]{10};
                Lease lease = acquire("phone", host, () -> {});
                host.failStart = true;
                try { lease.start(); throw new AssertionError("failure hidden"); }
                catch (IllegalStateException expected) {}
                host.failStart = false; lease.start();
                check(host.starts == 1, "failed start can retry");
                host.failStop = true;
                try { lease.release(); throw new AssertionError("failure hidden"); }
                catch (IllegalStateException expected) {}
                check(ACTIVE.isEmpty() && host.changed == null && host.clears == 1, "failure releases UI references");
                check(host.ids.length == 1, "binding remains recoverable");
            }
            """);
    }

    private static String fixture() throws Exception {
        return """
            private static final Map<String, Lease> ACTIVE = new HashMap<>();
            static class Context {
                static final int MODE_PRIVATE = 0;
                Object getSharedPreferences(String key, int mode) { return null; }
            }
            static class DesktopShellActivity extends Context {
                Profile appProfile() { return new Profile(); }
                Context getApplicationContext() { return this; }
                int getCurrentDisplayId() { return 0; }
            }
            static class Profile { long serialNumber; }
            static class ExternalDisplayController { static String getDisplayUniqueId(int id) { return "phone"; } }
            static class DesktopWidgetHostIds {
                DesktopWidgetHostIds(Object preferences) {}
                static String workspaceKey(long profile, String unique) { return unique; }
                int getOrAllocate(String workspace) { return 1; }
            }
            static void requireMainThread() {}
            static class Host {
                Runnable changed;
                int starts, stops, clears;
                int[] ids;
                boolean failStart, failStop;
                Host(Context context, int id) {}
                void startListening() { if (failStart) throw new IllegalStateException(); starts++; }
                void stopListening() { if (failStop) throw new IllegalStateException(); stops++; }
                void releaseViews() { clears++; }
                int[] getAppWidgetIds() { return ids; }
                void emit() { if (changed != null) changed.run(); }
            }
            """ + RuntimeSourceFixture.topLevelMethods("DesktopWidgetHosts", "acquire")
                + RuntimeSourceFixture.nestedClass("DesktopWidgetHosts", "Lease");
    }
}
