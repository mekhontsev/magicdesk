package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Deterministic reproductions for the runtime audit, without a phone runtime. */
public final class RuntimeAuditRegressionTest {
    private static final String NUBIA = "platform/nubia/";

    @Test
    public void watcherUsesRelayOwnershipAtItsProductionCallSite() throws Exception {
        RuntimeSourceFixture.verify("""
                static class ShellAccess { static boolean isReady() { return true; } }
                static class Log { static void i(String tag, String message) {} }
                static class KeyboardShortcutWatcher {
                    static boolean active;
                    static void start() { active = true; }
                    static void stop() { active = false; }
                }
                static final String TAG = "test";
                boolean mHasHardwareKeyboard = true, mKeyboardWatcherRunning, relay;
                boolean requiresInputRouting() { return relay; }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.updateKeyboardWatcher();
                    check(KeyboardShortcutWatcher.active, "unowned keyboard watcher is disabled");
                    f.relay = true;
                    f.updateKeyboardWatcher();
                    check(!KeyboardShortcutWatcher.active, "watcher competes with relay ownership");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator",
                "updateKeyboardWatcher", "shouldRunKeyboardWatcher"));
    }

    @Test
    public void hotplugFailureLeavesEveryAttemptJournaledBeforeAssociation() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Device { String location; Device(String p) { location = p; } }
                static class DesktopKeyboardDevice extends Device {
                    DesktopKeyboardDevice(String p) { super(p); }
                }
                static class DesktopMouseDevice extends Device {
                    DesktopMouseDevice(String p) { super(p); }
                }
                static class DesktopInputDeviceDiscovery {
                    static List<DesktopKeyboardDevice> findRoutableKeyboards() {
                        return List.of(new DesktopKeyboardDevice("new-keyboard"));
                    }
                    static List<DesktopMouseDevice> findRoutableMice() {
                        return List.of(new DesktopMouseDevice("new-mouse"));
                    }
                }
                static class DesktopInputRoutingOwnership {
                    static Set<String> journal = new LinkedHashSet<>(List.of("existing"));
                    static boolean fail;
                    static void record(Set<String> ports) throws IOException {
                        if (fail) throw new IOException("journal failed");
                        journal = new LinkedHashSet<>(ports);
                    }
                }
                static class Pointer {
                    boolean supportsDisplay(int id) { return false; }
                    void refreshViewport() {}
                }
                final Set<String> mAssociatedInputPorts = new LinkedHashSet<>(List.of("existing"));
                Object mInputManager = this, mAssociationTarget = "display";
                Method mAddAssociation;
                Pointer mPointer = new Pointer();
                boolean mClosed, mRouteKeyboards = true, mRoutePhysicalMice = true, mRouteVirtualMouse;
                int mDisplayId = 7, mKeyboardAssociationCount, calls;
                boolean journaledBefore = true;
                public void add(String port, String target) throws IOException {
                    calls++;
                    journaledBefore &= DesktopInputRoutingOwnership.journal.contains(port);
                    if (port.equals("new-mouse")) throw new IOException("second association failed");
                }
                List<DesktopMouseDevice> selectRoutedMice(List<DesktopMouseDevice> mice,
                        boolean physical, boolean virtual) { return mice; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.mAddAssociation = Fixture.class.getMethod("add", String.class, String.class);
                    try { f.refreshAssociations(); throw new AssertionError("failure expected"); }
                    catch (InvocationTargetException expected) {
                        check(expected.getCause() instanceof IOException, expected.getCause().toString());
                    }
                    check(f.mAssociatedInputPorts.equals(Set.of("existing", "new-keyboard")),
                            "successful first association was lost");
                    check(f.journaledBefore, "hotplug mutations preceded recovery journal");
                    check(DesktopInputRoutingOwnership.journal.containsAll(
                            List.of("existing", "new-keyboard", "new-mouse")), "recovery ownership lost");
                    DesktopInputRoutingOwnership.fail = true;
                    f.calls = 0;
                    try { f.refreshAssociations(); throw new AssertionError("journal failure expected"); }
                    catch (IOException expected) {}
                    check(f.calls == 0, "association ran despite journal failure");
                }
                """ + RuntimeSourceFixture.methods("DesktopInputRoutingSession",
                "refreshAssociations", "hasUnassociatedMouse", "associatePort", "addRequestedPort"));
    }

    @Test
    public void unrelatedDisplayReleasePreservesTouchpadRequest() throws Exception {
        RuntimeSourceFixture.verify("""
                static class MagicDeskRuntime {
                    static boolean requested = true;
                    static void setPhoneTouchpadRequested(boolean value) { requested = value; }
                }
                static class MagicDeskTouchpadActivity {
                    static int requestedDisplay = 7;
                    static boolean isRequested(int id) { return requestedDisplay == id; }
                    static void release(int id) {
                        if (requestedDisplay == id) requestedDisplay = -1;
                    }
                }
                public static void verify() {
                    release(9);
                    check(MagicDeskRuntime.requested, "unrelated display cleared shell request");
                    check(MagicDeskTouchpadActivity.isRequested(7), "unrelated activity released");
                    release(7);
                    check(!MagicDeskRuntime.requested, "matching shell request retained");
                    check(!MagicDeskTouchpadActivity.isRequested(7), "matching activity retained");
                }
                """ + RuntimeSourceFixture.methods("PhoneTouchpadController", "release"));
    }

    @Test
    public void statisticsRequestsCannotOverwriteAnUnconsumedReply() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final long RESPONSE_TIMEOUT_MILLIS = 1000L;
                final Object mRequestLock = new Object();
                final String mResponsePrefix = "STATS ";
                long mRequestSequence;
                NativeInputBridgeStats mLatestStats;
                record Result(String detail, String error) {}
                static class SystemClock {
                    static long uptimeMillis() { return System.nanoTime() / 1000000L; }
                }
                static class ShellStreamHandle {
                    final Fixture owner;
                    final BlockingQueue<Long> writes = new LinkedBlockingQueue<>();
                    final CountDownLatch secondEntered = new CountDownLatch(1);
                    ShellStreamHandle(Fixture f) { owner = f; }
                    void writeLine(String line) throws IOException {
                        long id = Long.parseLong(line.substring(6));
                        writes.add(id);
                        if (id == 2) {
                            synchronized (owner) {
                                owner.accept("STATS request=1 first");
                                owner.accept("STATS request=2 second");
                            }
                        }
                    }
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    ShellStreamHandle stream = new ShellStreamHandle(f);
                    ExecutorService executor = Executors.newFixedThreadPool(2);
                    try {
                        Future<Result> first = executor.submit(() -> f.request(stream));
                        check(Long.valueOf(1).equals(stream.writes.poll(2, TimeUnit.SECONDS)),
                                "first request not sent");
                        Future<Result> second = executor.submit(() -> {
                            stream.secondEntered.countDown(); return f.request(stream);
                        });
                        check(stream.secondEntered.await(2, TimeUnit.SECONDS), "second not scheduled");
                        Long competing = stream.writes.poll(200, TimeUnit.MILLISECONDS);
                        if (competing == null) f.accept("STATS request=1 first");
                        Result one = first.get(3, TimeUnit.SECONDS);
                        Result two = second.get(3, TimeUnit.SECONDS);
                        check(one.error().isEmpty() && one.detail().equals("first"),
                                "first reply lost: " + one);
                        check(two.error().isEmpty() && two.detail().equals("second"),
                                "second reply lost: " + two);
                    } finally {
                        executor.shutdownNow();
                        check(executor.awaitTermination(3, TimeUnit.SECONDS), "request threads retained");
                    }
                }
                """ + RuntimeSourceFixture.methods("NativeInputBridgeStatsClient",
                "request", "requestSerialized", "accept", "usefulMessage"),
                "NativeInputBridgeStats", "EventDrivenWaits");
    }

    @Test
    public void fractionalRefreshUsesTheSameRoundedIdentityAsSelection() throws Exception {
        RuntimeSourceFixture.verify("""
                static final long MODE_TIMEOUT_MS = 1000, MODE_POLL_MS = 100;
                static class SystemClock { static long now; static long uptimeMillis() { return now; } }
                static class BoundedStateAwaiter {
                    enum Reason { VENDOR_STATE }
                    static void pause(Reason r, long ms) { SystemClock.now += ms; }
                }
                static class ExternalDisplayController { static int findExternalDisplayId() { return 7; } }
                static class Context {
                    DisplayManager manager = new DisplayManager();
                    DisplayManager getSystemService(Class<?> type) { return manager; }
                }
                static class DisplayManager { Display display = new Display();
                    Display getDisplay(int id) { return display; } }
                static class Display {
                    static final int DEFAULT_DISPLAY = 0;
                    Mode mode = new Mode(); Mode getMode() { return mode; }
                    static class Mode {
                        float hz; int getPhysicalWidth() { return 1920; }
                        int getPhysicalHeight() { return 1080; } float getRefreshRate() { return hz; }
                    }
                }
                static class Mode { int width = 1920, height = 1080, refreshRate; }
                public static void verify() {
                    Context context = new Context(); Mode expected = new Mode();
                    for (float rate : new float[] {59.94f, 119.88f, 143.856f}) {
                        context.manager.display.mode.hz = rate;
                        expected.refreshRate = Math.round(rate); SystemClock.now = 0;
                        check(waitForMode(context, expected) == 7, "fractional mode rejected: " + rate);
                    }
                    context.manager.display.mode.hz = 60; expected.refreshRate = 120; SystemClock.now = 0;
                    check(waitForMode(context, expected) == -1, "different refresh accepted");
                }
                """ + RuntimeSourceFixture.methods(NUBIA + "NubiaHdmiModeController", "waitForMode"));
    }

    @Test
    public void coolingMonitoringUsesResolvedNamespaceAndPreservesUnknownFlow() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk.platform.nubia", """
                static final String VENDOR_FAN_EFFECTIVE = RedmagicHardwareSettings.FAN_EFFECTIVE;
                static final String VENDOR_PUMP_EFFECTIVE = RedmagicHardwareSettings.PUMP_EFFECTIVE;
                static final String VENDOR_PUMP_MAIN = RedmagicHardwareSettings.PUMP_MAIN;
                static final String VENDOR_PUMP_FLOW = RedmagicHardwareSettings.PUMP_FLOW;
                static RedmagicHardwareSnapshot sample(String namespace, String flow) throws Exception {
                    String shim = "settings() { case \\\"$*\\\" in "
                            + "'get global game_fan_off_on') printf 1;; "
                            + "'get system liquid_cooling_off_on') printf 1;; "
                            + "'get " + namespace + " liquid_cooling_main_switch') printf 1;; "
                            + "'get " + namespace + " liquid_cooling_flow_speed_mode') printf '%s' '"
                            + flow + "';; *) printf null;; esac; }; :";
                    Process process = new ProcessBuilder("sh", "-c", shim
                            + vendorMonitoringCommand().replace("/system/bin/settings", "settings"))
                            .redirectErrorStream(true).start();
                    try {
                        String output = new String(process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0, output);
                        return RedmagicHardwareSnapshot.parse(output);
                    } finally { process.destroyForcibly(); }
                }
                public static void verify() throws Exception {
                    for (String namespace : List.of("global", "system")) {
                        for (String flow : List.of("low", "mid", "fast")) {
                            int expected = flow.equals("low") ? 60 : flow.equals("mid") ? 70 : 80;
                            check(sample(namespace, flow).pumpSpeed == expected,
                                    "wrong pump flow for " + namespace + ":" + flow);
                        }
                        check(sample(namespace, "null").pumpSpeed == RedmagicHardwareSnapshot.UNKNOWN,
                                "missing flow fabricated as Fast");
                        check(sample(namespace, "unexpected").pumpSpeed == RedmagicHardwareSnapshot.UNKNOWN,
                                "unknown flow fabricated as Fast");
                    }
                }
                """ + RuntimeSourceFixture.methods(NUBIA + "RedmagicHardwareController", "vendorMonitoringCommand"),
                NUBIA + "RedmagicHardwareSettings", NUBIA + "RedmagicSettingsNamespace",
                NUBIA + "RedmagicHardwareSnapshot");
    }

    @Test
    public void coolingSystemSpeedsRoundTripToManualLevels() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk.platform.nubia", """
                static class RedmagicHardwareController { enum PumpMode { SYSTEM, OFF, SLOW, MEDIUM, FAST } }
                public static void verify() {
                    Fixture f = new Fixture();
                    for (int speed : new int[] {60, 70, 80}) {
                        RedmagicHardwareSnapshot snapshot = RedmagicHardwareSnapshot.parse(
                                "node.pump_enable=1\\nnode.pump_speed=" + speed);
                        check(f.resolvePumpSpeed(snapshot, RedmagicHardwareController.PumpMode.SYSTEM)
                                == (speed - 50) / 10, "incorrect manual level for " + speed);
                    }
                }
                """ + RuntimeSourceFixture.methods(NUBIA + "RedmagicHardwarePanelController",
                "resolvePumpSpeed", "isManual"), NUBIA + "RedmagicHardwareSettings",
                NUBIA + "RedmagicSettingsNamespace", NUBIA + "RedmagicHardwareSnapshot");
    }

    @Test
    public void pointerSliderWritesNonTouchChangesAndBatchesTouch() throws Exception {
        slider("PointerSpeedPanelController", """
                boolean mTracking;
                void updateValue(int value) {}
                void apply(int value) { writes.add(value); }
                """);
    }

    @Test
    public void coolingSliderWritesNonTouchChangesAndBatchesTouch() throws Exception {
        slider(NUBIA + "RedmagicHardwarePanelController", """
                boolean mUpdatingControls, mTrackingPumpSpeed;
                void updatePumpSpeedStatus(int value) {}
                int pumpModeForSpeed(int value) { return value; }
                void applyPumpMode(int value) { writes.add(value); }
                """);
    }

    private static void slider(final String file, final String state) throws Exception {
        RuntimeSourceFixture.verify(state + """
                final List<Integer> writes = new ArrayList<>();
                static class SeekBar { int getProgress() { return 3; } }
                public static void verify() {
                    Fixture f = new Fixture(); SeekBar bar = new SeekBar();
                    f.onProgressChanged(bar, 1, false);
                    check(f.writes.isEmpty(), "programmatic update wrote setting");
                    f.onProgressChanged(bar, 2, true);
                    check(f.writes.equals(List.of(2)), "non-touch user update was dropped");
                    f.writes.clear(); f.onStartTrackingTouch(bar);
                    f.onProgressChanged(bar, 2, true); f.onProgressChanged(bar, 3, true);
                    check(f.writes.isEmpty(), "touch update was not batched");
                    f.onStopTrackingTouch(bar);
                    check(f.writes.equals(List.of(3)), "touch end did not write once");
                }
                """ + RuntimeSourceFixture.methods(file,
                "onProgressChanged", "onStartTrackingTouch", "onStopTrackingTouch"));
    }

    @Test
    public void vanishedPtyDoesNotPublishTheProcLinkAsItsDirectory() throws Exception {
        RuntimeSourceFixture.verify("""
                long pid = Long.MAX_VALUE;
                long processId() { return pid; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    try {
                        String result = f.workingDirectory();
                        throw new AssertionError("vanished PTY reported cwd=" + result);
                    } catch (IOException expected) {}
                    f.pid = ProcessHandle.current().pid();
                    check(f.workingDirectory().equals(Path.of(".").toRealPath().toString()),
                            "live PTY directory did not resolve");
                }
                """ + RuntimeSourceFixture.methods("ShizukuCommandService", "workingDirectory"));
    }
}
