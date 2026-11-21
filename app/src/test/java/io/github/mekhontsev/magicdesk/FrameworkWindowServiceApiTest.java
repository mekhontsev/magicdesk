package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import org.junit.Test;

public final class FrameworkWindowServiceApiTest {
    @Test public void standaloneWindowingDoesNotRequireApplicationSharedMemory() throws Exception {
        verify("""
            try {
                android.view.WindowManagerGlobal.getWindowManagerService();
                throw new AssertionError("fixture did not reproduce missing shared memory");
            } catch(IllegalStateException expected) {
                check(expected.getMessage().equals("ApplicationSharedMemory not initialized"), "wrong failure");
            }
            var api = new FrameworkDisplayWindowingApi();
            var before = api.read(7);
            check(before.displayId == 7 && before.mode == 1 && before.uniqueId.equals("external-7"), "snapshot");
            api.set(7, "external-7", 5);
            check(api.read(7).mode == 5, "window mode not applied");
            check(android.view.WindowManagerGlobal.calls == 1, "application UI initialized by service lookup");
            check(android.view.IWindowManager.Stub.received == android.os.ServiceManager.binder, "Binder substituted");
            check(new FrameworkDisplayImeApi().get(7) == 0, "IME path lost");
            new FrameworkDisplayImeApi().set(7, 1);
            check(new FrameworkDisplayImeApi().get(7) == 1, "IME write lost");
            check(android.os.ServiceManager.calls == 1, "repeated service-manager lookup");
            """);
    }

    @Test public void preservesDisplayIdentityChecksAndMissingDisplaySemantics() throws Exception {
        verify("""
            var api = new FrameworkDisplayWindowingApi();
            check(api.read(-1) == null, "missing display fabricated");
            try { api.set(7, "wrong-display", 5); throw new AssertionError("wrong display changed"); }
            catch(IllegalStateException expected) {}
            check(api.read(7).mode == 1, "rejected operation wrote mode");
            check(android.view.WindowManagerGlobal.calls == 0, "application runtime accessed");
            """);
    }

    @Test public void unavailableBinderOrInterfaceCanBeRetriedWithoutPoisoningCache() throws Exception {
        verify("""
            var binder = android.os.ServiceManager.binder;
            android.os.ServiceManager.binder = null;
            try { FrameworkWindowServiceApi.get(); throw new AssertionError("missing binder accepted"); }
            catch(IllegalStateException expected) {}
            android.os.ServiceManager.binder = binder;
            var service = android.view.IWindowManager.Stub.service;
            android.view.IWindowManager.Stub.service = null;
            try { FrameworkWindowServiceApi.get(); throw new AssertionError("missing interface accepted"); }
            catch(IllegalStateException expected) {}
            android.view.IWindowManager.Stub.service = service;
            check(FrameworkWindowServiceApi.get() == service, "recovery failed");
            check(FrameworkWindowServiceApi.get() == service, "cache changed");
            check(android.os.ServiceManager.calls == 3, "negative result cached or success not cached");
            check(android.view.WindowManagerGlobal.calls == 0, "fallback to application runtime");
            """);
    }

    @Test public void frameworkDenialIsNotHiddenOrRetriedThroughAnotherIdentity() throws Exception {
        verify("""
            android.os.ServiceManager.denied = true;
            try { FrameworkWindowServiceApi.get(); throw new AssertionError("permission denial hidden"); }
            catch(InvocationTargetException expected) {
                check(expected.getCause() instanceof SecurityException, "failure cause lost");
            }
            check(android.os.ServiceManager.calls == 1, "implicit retry");
            check(android.view.WindowManagerGlobal.calls == 0, "fallback used");
            android.os.ServiceManager.denied = false;
            check(FrameworkWindowServiceApi.get() != null, "explicit retry failed");
            """);
    }

    @Test public void concurrentConsumersShareOneConnection() throws Exception {
        verify("""
            var start = new CountDownLatch(1);
            var jobs = new ArrayList<FutureTask<Object>>();
            for(int i = 0; i < 16; i++) {
                var job = new FutureTask<Object>(() -> { start.await(); return FrameworkWindowServiceApi.get(); });
                jobs.add(job);
                new Thread(job).start();
            }
            start.countDown();
            for(var job : jobs) check(job.get(5, TimeUnit.SECONDS) == android.view.IWindowManager.Stub.service,
                "inconsistent connection");
            check(android.os.ServiceManager.calls == 1, "multiple connections");
            """);
    }

    private static void verify(String body) throws Exception {
        final var sources = new LinkedHashMap<String, String>();
        sources.put("IBinder", "package android.os; public interface IBinder {}");
        sources.put("ServiceManager", """
            package android.os;
            public class ServiceManager {
                public static IBinder binder = new IBinder() {};
                public static boolean denied;
                public static int calls;
                public static IBinder getService(String name) {
                    if (!name.equals("window")) throw new AssertionError("unexpected service " + name);
                    calls++;
                    if (denied) throw new SecurityException("denied");
                    return binder;
                }
            }
            """);
        sources.put("IWindowManager", """
            package android.view;
            public interface IWindowManager {
                int getWindowingMode(int id);
                void setWindowingMode(int id, int mode);
                int getDisplayImePolicy(int id);
                void setDisplayImePolicy(int id, int policy);
                class Stub {
                    public static android.os.IBinder received;
                    public static IWindowManager service = new Impl();
                    public static IWindowManager asInterface(android.os.IBinder binder) {
                        received = binder;
                        return service;
                    }
                }
                class Impl implements IWindowManager {
                    private int mode = 1, ime;
                    public int getWindowingMode(int id) { return mode; }
                    public void setWindowingMode(int id, int value) { mode = value; }
                    public int getDisplayImePolicy(int id) { return ime; }
                    public void setDisplayImePolicy(int id, int value) { ime = value; }
                }
            }
            """);
        sources.put("WindowManagerGlobal", """
            package android.view;
            public class WindowManagerGlobal {
                public static int calls;
                public static IWindowManager getWindowManagerService() {
                    calls++;
                    throw new IllegalStateException("ApplicationSharedMemory not initialized");
                }
            }
            """);
        sources.put("Display", "package android.view; public class Display { public static final int TYPE_VIRTUAL = 5; }");
        sources.put("DisplayInfo", """
            package android.view;
            public class DisplayInfo { public String uniqueId = "external-7"; public int type = 2; }
            """);
        sources.put("DisplayManagerGlobal", """
            package android.hardware.display;
            public class DisplayManagerGlobal {
                public static DisplayManagerGlobal getInstance() { return new DisplayManagerGlobal(); }
                public android.view.DisplayInfo getDisplayInfo(int id) {
                    return id == 7 ? new android.view.DisplayInfo() : null;
                }
            }
            """);
        sources.put("DisplayWindowingSnapshot", """
            package io.github.mekhontsev.magicdesk;
            class DisplayWindowingSnapshot {
                final int displayId, mode; final String uniqueId; final boolean virtual;
                DisplayWindowingSnapshot(int displayId, String uniqueId, int mode, boolean virtual) {
                    this.displayId=displayId; this.uniqueId=uniqueId; this.mode=mode; this.virtual=virtual;
                }
            }
            """);
        final Path directory = Files.createTempDirectory("magicdesk-window-service-");
        try {
            final var paths = new java.util.ArrayList<String>();
            paths.add("FrameworkWindowServiceApi");
            paths.add("FrameworkDisplayWindowingApi");
            paths.add("FrameworkDisplayImeApi");
            for (final var entry : sources.entrySet()) {
                final Path file = directory.resolve(entry.getKey() + ".java");
                Files.writeString(file, entry.getValue());
                paths.add(file.toString());
            }
            RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                    "public static void verify() throws Exception {" + body + "}", paths.toArray(String[]::new));
        } finally {
            try (var files = Files.walk(directory)) {
                for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
