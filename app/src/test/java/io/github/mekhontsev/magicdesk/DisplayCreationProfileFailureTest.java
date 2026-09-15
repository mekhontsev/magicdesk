package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayCreationProfileFailureTest {
    @Test public void profileFailureRetainsTheAllocatedIdentityAndCallbackRunsOnlyOnce() throws Exception {
        RuntimeSourceFixture.verify("""
                interface DisplayCallback { void onComplete(DesktopDisplayInfo display, String error); }
                static class DesktopDisplayInfo { int id = 8; String uniqueId = "created:8"; }
                static class VirtualDisplaySpec { void requireOverlayCompatible() { } }
                static class TaskCommandQueue { static void execute(Runnable action) { action.run(); } }
                static class SimulatedDesktopDisplayController { static int create(VirtualDisplaySpec spec) { return 8; } }
                static class DesktopDisplayCatalog {
                    static DesktopDisplayInfo require(int id, String uniqueId) { return ShellAccess.created; }
                }
                static class ShellAccess {
                    static final DesktopDisplayInfo created = new DesktopDisplayInfo();
                    static int calls;
                    static DesktopDisplayInfo createVirtualDisplay(VirtualDisplaySpec spec) throws IOException {
                        ++calls; return created;
                    }
                }
                static class DisplayProfiles {
                    static Object createdProfile(DesktopDisplayInfo d, VirtualDisplaySpec s) { return d; }
                }
                static class DisplayProfileStore {
                    static boolean writable;
                    static boolean save(Object p) { return writable; }
                }
                static class MagicDeskApplication { static Object applicationContext() { return null; } }
                static class VirtualDisplayPreferences { static void save(Object c, VirtualDisplaySpec s) { } }
                static class CompatibilityDiagnostics {
                    static void record(String code, String title, String detail, Throwable error) { }
                }
                public static void verify() {
                    List<DesktopDisplayInfo> results = new ArrayList<>();
                    List<String> errors = new ArrayList<>();
                    DisplayCallback callback = (display, error) -> { results.add(display); errors.add(error); };
                    createDisplay(new VirtualDisplaySpec(), false, callback);
                    check(results.get(0) == ShellAccess.created, "profile failure lost allocated identity");
                    check(errors.get(0).contains("created:8") && errors.get(0).contains("retained"),
                            "partial failure does not identify the retained display");
                    check(ShellAccess.calls == 1, "creation was retried");
                    DisplayProfileStore.writable = true;
                    createDisplay(new VirtualDisplaySpec(), false, callback);
                    check(results.size() == 2 && errors.get(1) == null, "saved profile did not complete creation");
                    int[] callbacks = {0};
                    try {
                        createDisplay(new VirtualDisplaySpec(), false, (display, error) -> {
                            ++callbacks[0]; throw new IllegalStateException("consumer failed");
                        });
                        throw new AssertionError("consumer failure was swallowed");
                    } catch (IllegalStateException expected) {
                        check(callbacks[0] == 1, "consumer called twice after throwing");
                    }
                }
                """ + RuntimeSourceFixture.methods("DisplayOperations", "createDisplay"));
    }
}
