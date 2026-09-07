package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class TaskAreaBackstopInputTest {
    @Test
    public void configuresEveryActivityInstanceBeforeCreatingItsWindow() throws Exception {
        verify("""
                Intent intent = Anchor.createIntent("fullscreen-slot:7:42");
                Anchor first = new Anchor(intent);
                first.onCreate(null);
                check(events.equals(List.of("super", "sink", "flags", "back", "content")),
                        "input policy was not established before the window: " + events);
                check(configured.equals(List.of(first.token)), "wrong Activity token");
                check(!first.finished && first.content, "valid anchor did not initialize");
                events.clear();
                Anchor recreated = new Anchor(intent);
                recreated.onCreate(new Bundle());
                check(configured.equals(List.of(first.token, recreated.token)),
                        "recreated Activity inherited the old token's input policy");
                check(recreated.content, "recreated anchor did not initialize");
                """);
    }

    @Test
    public void missingOrFailedPolicyCannotLeaveAnInputBlocker() throws Exception {
        verify("""
                for (String failure : List.of("missing", "binder", "permission")) {
                    Intent intent = Anchor.createIntent("fullscreen-slot:7:42");
                    if (failure.equals("missing")) intent = new Intent();
                    if (failure.equals("binder")) {
                        intent.extras.putBinder(Anchor.EXTRA_INPUT_POLICY,
                                new IActivityInputPolicy.Stub() {
                                    public void disableInputSink(IBinder token) throws RemoteException {
                                        throw new RemoteException();
                                    }
                                });
                    }
                    failPolicy = failure.equals("permission");
                    events.clear();
                    Anchor anchor = new Anchor(intent);
                    anchor.onCreate(null);
                    check(anchor.finished, "failed anchor remained active: " + failure);
                    check(!anchor.content && !events.contains("flags"),
                            "failed anchor exposed a window: " + failure);
                    check(events.contains("error"), "failure was not reported: " + failure);
                }
                """);
    }

    @Test
    public void inputRegistrationIsSynchronous() throws Exception {
        final String aidl = Files.readString(Path.of(
                "src/main/aidl/io/github/mekhontsev/magicdesk/IActivityInputPolicy.aidl"));
        assertFalse(aidl.contains("oneway"));
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>();
                static final List<IBinder> configured = new ArrayList<>();
                static boolean failPolicy;
                interface IBinder {}
                static class RemoteException extends Exception {}
                interface IActivityInputPolicy {
                    void disableInputSink(IBinder token) throws RemoteException;
                    abstract class Stub implements IActivityInputPolicy, IBinder {
                        IBinder asBinder() { return this; }
                        static IActivityInputPolicy asInterface(IBinder binder) {
                            return (IActivityInputPolicy) binder;
                        }
                    }
                }
                static class Bundle {
                    final Map<String, IBinder> binders = new HashMap<>();
                    void putBinder(String key, IBinder binder) { binders.put(key, binder); }
                    IBinder getBinder(String key) { return binders.get(key); }
                }
                static class Intent {
                    static final int FLAG_ACTIVITY_NEW_TASK = 1, FLAG_ACTIVITY_NEW_DOCUMENT = 2,
                            FLAG_ACTIVITY_MULTIPLE_TASK = 4, FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 8,
                            FLAG_ACTIVITY_NO_ANIMATION = 16;
                    Bundle extras;
                    Intent setComponent(Object component) { return this; }
                    Intent setData(Object data) { return this; }
                    Intent addFlags(int flags) { return this; }
                    Intent putExtras(Bundle value) { extras = value; return this; }
                    Bundle getExtras() { return extras; }
                }
                static class Uri {
                    static String encode(String value) { return value; }
                    static String parse(String value) { return value; }
                }
                static class FrameworkActivityInputApi {
                    static IBinder requireActivityToken(Activity activity) { return activity.token; }
                    static void setRecordInputSinkEnabled(IBinder token, boolean enabled) {
                        check(!enabled, "anchor input sink was enabled");
                        if (failPolicy) throw new SecurityException("denied");
                        events.add("sink"); configured.add(token);
                    }
                }
                static class WindowManager {
                    static class LayoutParams { static final int FLAG_NOT_TOUCHABLE = 16; }
                }
                static class Window {
                    void addFlags(int flags) {
                        check(flags == WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                                "unrelated window policy changed");
                        events.add("flags");
                    }
                }
                static class OnBackInvokedDispatcher {
                    static final int PRIORITY_DEFAULT = 0;
                    void registerOnBackInvokedCallback(int priority, Runnable action) {
                        events.add("back");
                    }
                }
                static class Color { static final int TRANSPARENT = 0; }
                static class View {
                    static final int IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4;
                    View(Activity activity) {}
                    void setBackgroundColor(int color) {}
                    void setImportantForAccessibility(int value) {}
                }
                static class Log {
                    static void e(String tag, String message, Throwable error) { events.add("error"); }
                }
                static class Activity {
                    final IBinder token = new IBinder() {};
                    Intent intent;
                    boolean finished, content;
                    protected void onCreate(Bundle state) { events.add("super"); }
                    Intent getIntent() { return intent; }
                    void finishAndRemoveTask() { finished = true; }
                    Window getWindow() { return new Window(); }
                    OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
                        return new OnBackInvokedDispatcher();
                    }
                    void setContentView(View view) { content = true; events.add("content"); }
                }
                static class Anchor extends Activity {
                    static final Object COMPONENT = new Object();
                    static final String TAG = "test", EXTRA_INPUT_POLICY = "input_policy";
                    Anchor(Intent value) { intent = value; }
                """ + RuntimeSourceFixture.methods("TaskAreaBackstopActivity", "createIntent", "onCreate")
                + "}\npublic static void verify() throws Exception {\n" + scenario + "}\n");
    }
}
