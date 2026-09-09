package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopHomeDelegateOwnershipTest {
    @Test
    public void sharedChromeRootIsUntouchedAndDelegateLeafIsTransparent() throws Exception {
        verify("""
                fixture.configureDesktopHomeDelegate(0, 976, activity);
                check(committed.equals(policy("task:976")), "changed a shared root: " + committed);
                check(rootQueries.isEmpty(), "resolved a foreign root token");
                check(sinks.equals(List.of(activity)), "wrong Activity input sink");
                check(commits == 1, "delegate setup must have one transaction owner");
                """);
    }

    @Test
    public void independentHomeRootRetainsItsPolicy() throws Exception {
        verify("""
                delegate.rootTaskId = 900;
                fixture.configureDesktopHomeDelegate(0, 976, activity);
                List<String> expected = policy("task:976");
                expected.addAll(policy("root:900"));
                check(committed.equals(expected), "lost independent-root policy: " + committed);
                check(rootQueries.equals(List.of(900)), "wrong independent root");
                check(commits == 1, "root and leaf must commit together");
                """);
    }

    @Test
    public void delegateWhichIsItselfARootIsConfiguredOnlyOnce() throws Exception {
        verify("""
                delegate.rootTaskId = delegate.taskId;
                fixture.configureDesktopHomeDelegate(0, 976, activity);
                check(committed.equals(policy("task:976")), "duplicate root/leaf changes");
                check(rootQueries.isEmpty(), "root token unnecessarily re-queried");
                """);
    }

    @Test
    public void otherAreaDoesNotDependOnChromeAvailability() throws Exception {
        verify("""
                delegate.displayAreaFeatureId = 42;
                delegate.rootTaskId = 900;
                tasks.remove(975);
                fixture.configureDesktopHomeDelegate(0, 976, activity);
                check(rootQueries.equals(List.of(900)), "did not configure other area's HOME");
                check(!taskQueries.contains(975), "queried unrelated chrome");
                """);
    }

    @Test
    public void ownershipUsesLiveRootIdentityAndNotActivityType() throws Exception {
        verify("""
                chrome.home = false;
                check(fixture.mDesktopChromeHost.ownsRoot(delegate), "activity type chose owner");
                chrome.rootTaskId = 800;
                check(!fixture.mDesktopChromeHost.ownsRoot(delegate), "retained stale root owner");
                chrome.rootTaskId = delegate.rootTaskId;
                delegate.displayId = 7;
                check(!fixture.mDesktopChromeHost.ownsRoot(delegate), "ignored display identity");
                delegate.displayId = 0;
                delegate.displayAreaFeatureId = 42;
                check(!fixture.mDesktopChromeHost.ownsRoot(delegate), "ignored area identity");
                fixture.mDesktopChromeHost.mArea = null;
                check(!fixture.mDesktopChromeHost.ownsRoot(delegate), "closed host retained ownership");
                """);
    }

    @Test
    public void unresolvedChromeOwnershipCannotMutateItsRoot() throws Exception {
        for (final String change : new String[] {
                "tasks.remove(975);",
                "chrome.rootTaskId = -1;",
                "chrome.displayAreaFeatureId = 42;",
                "chrome.rootComponent = \"unrelated\";",
                "failLookupTaskId = 975;"
        }) {
            verify(change + """
                    expectRejected(() -> fixture.configureDesktopHomeDelegate(0, 976, activity));
                    check(committed.isEmpty() && sinks.isEmpty() && rootQueries.isEmpty(),
                            "unknown ownership changed window/input state");
                    """);
        }
    }

    @Test
    public void invalidDelegatesAreRejectedBeforeAnyMutation() throws Exception {
        for (final String change : new String[] {
                "fixture.mConfiguredDisplayId = 7;",
                "fixture.mClosed = true;",
                "tasks.remove(976);",
                "tasks.remove(973);",
                "delegate.home = false;",
                "delegate.componentName = \"unrelated\";",
                "delegate.rootTaskId = 1;",
                "delegate.rootTaskId = -1;",
                "host.rootTaskId = -1;"
        }) {
            verify(change + """
                    expectRejected(() -> fixture.configureDesktopHomeDelegate(0, 976, activity));
                    check(committed.isEmpty() && sinks.isEmpty() && rootQueries.isEmpty(),
                            "invalid delegate changed window/input state");
                    """);
        }
    }

    @Test
    public void missingActivityTokenIsRejectedBeforeReadingTasks() throws Exception {
        verify("""
                expectRejected(() -> fixture.configureDesktopHomeDelegate(0, 976, null));
                check(taskQueries.isEmpty() && committed.isEmpty() && sinks.isEmpty(),
                        "invalid Activity token reached task configuration");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                interface IBinder {}
                static final Map<Integer, FrameworkTaskSnapshot> tasks = new HashMap<>();
                static final List<Integer> taskQueries = new ArrayList<>(), rootQueries = new ArrayList<>();
                static final List<String> committed = new ArrayList<>();
                static final List<IBinder> sinks = new ArrayList<>();
                static int commits, failLookupTaskId = -1;
                static class FrameworkTaskSnapshot {
                    final int taskId;
                    int rootTaskId, displayId, displayAreaFeatureId;
                    boolean home = true;
                    String componentName = "home", rootComponent = "home";
                    final Object task;
                    FrameworkTaskSnapshot(int id, int root, int area) {
                        taskId = id; rootTaskId = root; displayAreaFeatureId = area;
                        task = "task:" + id;
                    }
                    boolean isHome() { return home; }
                }
                static class FrameworkTaskSnapshotSource {
                    static FrameworkTaskSnapshot findTask(Object service, int display, int taskId)
                            throws ReflectiveOperationException {
                        taskQueries.add(taskId);
                        if (taskId == failLookupTaskId) throw new ReflectiveOperationException("unavailable");
                        FrameworkTaskSnapshot task = tasks.get(taskId);
                        return task != null && task.displayId == display ? task : null;
                    }
                }
                static class DesktopHostComponents {
                    static boolean isHostComponentName(String name) { return "home".equals(name); }
                }
                static class DesktopChromeActivity {
                    static boolean isChromeComponent(String component) { return "chrome".equals(component); }
                }
                static class Area { int featureId() { return 20; } }
                static class Chrome {
                    final Object mService = new Object();
                    Area mArea = new Area();
                    int mDisplayId, mTaskId = 975;
                """ + RuntimeSourceFixture.methods("ShellDesktopChromeHost", "ownsRoot") + "}\n" + """
                static class Ownership { int desktopHostTaskId() { return 973; } }
                static class HiddenTaskApi {
                    static Object getTaskToken(Object task) { return task; }
                    static Object requireRootTaskToken(Object service, int display, int root) {
                        check(display == 0, "wrong root display");
                        rootQueries.add(root);
                        return "root:" + root;
                    }
                }
                static class Transaction { final List<String> ops = new ArrayList<>(); }
                static class FrameworkWindowingApi {
                    Object newTransaction() { return new Transaction(); }
                    Class<?> transactionClass() { return Transaction.class; }
                    void setFocusable(Object transaction, Object token, boolean value) {
                        ((Transaction) transaction).ops.add("focus:" + token + ":" + value);
                    }
                    void setForceTranslucent(Object transaction, Object token, boolean value) {
                        ((Transaction) transaction).ops.add("translucent:" + token + ":" + value);
                    }
                    void reorder(Object transaction, Object token, boolean value) {
                        ((Transaction) transaction).ops.add("order:" + token + ":" + value);
                    }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
                }
                static class FrameworkActivityInputApi {
                    static void setRecordInputSinkEnabled(IBinder token, boolean enabled) {
                        check(!enabled, "enabled delegate input sink"); sinks.add(token);
                    }
                }
                static class ShellWindowTransitionExecutor {
                    static void applyAtomic(Object service, Class<?> type, Object transaction) {
                        check(type == Transaction.class, "wrong transaction class");
                        check(sinks.size() == 1, "input sink not configured before commit");
                        committed.addAll(((Transaction) transaction).ops); commits++;
                    }
                }
                static class Observer {
                    final Object mService = new Object();
                    final Ownership mDesktopOwnership = new Ownership();
                    final Chrome mDesktopChromeHost = new Chrome();
                    int mConfiguredDisplayId;
                    boolean mClosed;
                """ + RuntimeSourceFixture.methods("ShellTaskObserver", "configureDesktopHomeDelegate")
                + "}\n" + """
                static List<String> policy(String token) {
                    return new ArrayList<>(List.of("focus:" + token + ":false",
                            "translucent:" + token + ":true", "order:" + token + ":false"));
                }
                static void expectRejected(Runnable action) {
                    try { action.run(); throw new AssertionError("invalid delegate accepted"); }
                    catch (IllegalArgumentException | IllegalStateException expected) {}
                }
                public static void verify() throws Exception {
                    Observer fixture = new Observer();
                    FrameworkTaskSnapshot host = new FrameworkTaskSnapshot(973, 1, 1);
                    FrameworkTaskSnapshot chrome = new FrameworkTaskSnapshot(975, 974, 20);
                    chrome.rootComponent = "chrome";
                    FrameworkTaskSnapshot delegate = new FrameworkTaskSnapshot(976, 974, 20);
                    tasks.put(host.taskId, host); tasks.put(chrome.taskId, chrome);
                    tasks.put(delegate.taskId, delegate);
                    IBinder activity = new IBinder() {};
                """ + scenario + "}\n");
    }
}
