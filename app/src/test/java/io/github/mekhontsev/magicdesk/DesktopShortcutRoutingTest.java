package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopShortcutRoutingTest {
    @Test public void routedKeyboardsUseCommittedInventoryNotTheOtherProcessesCache() throws Exception {
        RuntimeSourceFixture.verify("""
                public static class InputDevice {
                    static final int KEYBOARD_TYPE_ALPHABETIC = 2;
                    int display; boolean virtual, external = true; int type = 2;
                    InputDevice(int target) { display = target; }
                    boolean isVirtual() { return virtual; }
                    boolean isExternal() { return external; }
                    int getKeyboardType() { return type; }
                    public int getAssociatedDisplayId() { return display; }
                    static int[] getDeviceIds() { throw new AssertionError("stale process cache"); }
                    static InputDevice getDevice(int id) { throw new AssertionError("stale process cache"); }
                }
                public static class InputManager {
                    Map<Integer, InputDevice> devices = new LinkedHashMap<>();
                    public int[] getInputDeviceIds() {
                        return devices.keySet().stream().mapToInt(Integer::intValue).toArray();
                    }
                    public InputDevice getInputDevice(int id) { return devices.get(id); }
                }
                final InputManager mInputManager = new InputManager();
                final java.lang.reflect.Method mGetInputDeviceIds = method("getInputDeviceIds");
                final java.lang.reflect.Method mGetInputDevice = method("getInputDevice", int.class);
                static java.lang.reflect.Method method(String name, Class<?>... args) {
                    try { return InputManager.class.getMethod(name, args); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
                """ + RuntimeSourceFixture.methods("FrameworkInputRoutingApi", "keyboardDeviceIds") + """
                public static void verify() throws Exception {
                    Fixture api = new Fixture();
                    InputDevice keyboard = new InputDevice(41);
                    api.mInputManager.devices.put(57, keyboard);
                    api.mInputManager.devices.put(58, new InputDevice(0));
                    InputDevice virtual = new InputDevice(41); virtual.virtual = true;
                    api.mInputManager.devices.put(59, virtual);
                    InputDevice internal = new InputDevice(41); internal.external = false;
                    api.mInputManager.devices.put(60, internal);
                    InputDevice buttons = new InputDevice(41); buttons.type = 1;
                    api.mInputManager.devices.put(61, buttons);
                    api.mInputManager.devices.put(62, null);
                    check(Arrays.equals(api.keyboardDeviceIds(41), new int[] {57}),
                            "committed keyboard route lost or foreign device admitted");
                    keyboard.display = 0;
                    check(api.keyboardDeviceIds(41).length == 0, "old route retained");
                    check(Arrays.equals(api.keyboardDeviceIds(0), new int[] {57, 58}),
                            "new route not observed without local cache invalidation");
                    api.mInputManager.devices.clear();
                    check(api.keyboardDeviceIds(0).length == 0, "disconnected keyboard retained");
                }
                """);
    }

    @Test public void callbacksRearmGenerationObservationAndDiscardStaleReplies() throws Exception {
        RuntimeSourceFixture.verify("""
                static class InputDevice {
                    static boolean materialized;
                    static int[] getDeviceIds() { return new int[] {57}; }
                    static InputDevice getDevice(int id) { materialized = true; return new InputDevice(); }
                }
                static class DesktopOperations {
                    static List<Runnable> work = new ArrayList<>();
                    static void executeSerialized(Runnable r) { work.add(r); }
                }
                static class Handler {
                    List<Runnable> replies = new ArrayList<>();
                    void post(Runnable r) { replies.add(r); }
                }
                static class ShellAccess {
                    static int[] ids = {57};
                    static int[] routedKeyboardDeviceIds(int display) throws IOException { return ids; }
                }
                static class CompatibilityDiagnostics {
                    static void record(String a, String b, String c, Exception d) { throw new AssertionError(d); }
                }
                static final Handler MAIN = new Handler();
                static int sTargetDisplay = 41;
                static DesktopShortcutService sInstance;
                static class DesktopShortcutService {
                    int mDeviceGeneration;
                    Set<Integer> mRoutedKeyboards = Set.of();
                """ + RuntimeSourceFixture.methods("DesktopShortcutService", "refreshRouting") + """
                }
                public static void verify() {
                    DesktopShortcutService service = sInstance = new DesktopShortcutService();
                    service.refreshRouting();
                    check(InputDevice.materialized, "route commit callback would be suppressed");
                    DesktopOperations.work.remove(0).run();
                    InputDevice.materialized = false;
                    sTargetDisplay = 42;
                    service.refreshRouting();
                    check(InputDevice.materialized, "next generation was not rearmed");
                    MAIN.replies.remove(0).run();
                    check(service.mRoutedKeyboards.isEmpty(), "old-target reply published");
                    ShellAccess.ids = new int[] {58};
                    DesktopOperations.work.remove(0).run();
                    MAIN.replies.remove(0).run();
                    check(service.mRoutedKeyboards.equals(Set.of(58)), "current route not published");
                    sTargetDisplay = -1;
                    service.refreshRouting();
                    check(service.mRoutedKeyboards.isEmpty(), "release retained shortcut eligibility");
                    check(DesktopOperations.work.isEmpty(), "inactive target queried shell");
                }
                """);
    }
}
