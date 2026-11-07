package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedKeyboardTargetTest {
    @Test public void admissionUsesTheFocusedTaskOnTheSelectedDisplayNotGlobalPhoneFocus() throws Exception {
        RuntimeSourceFixture.verify("""
            static class HostedKeyboardShortcuts { interface Target {} }
            static class Surface {
                HostedKeyboardShortcuts.Target target=new HostedKeyboardShortcuts.Target() {};
                HostedKeyboardShortcuts.Target keyboardShortcuts() { return target; }
            }
            static class HostedWindowPresentation {
                interface ContentHost { boolean wholeDesktopViewer(); Surface hostedSurface(); }
            }
            record Display(int getDisplayId) {}
            record Component(String flattenToShortString) {}
            static class Activity {
                int task=10,display=4; boolean destroyed,finishing;
                boolean isDestroyed() { return destroyed; } boolean isFinishing() { return finishing; }
                int getTaskId() { return task; } String getPackageName() { return "app"; }
                Display getDisplay() { return new Display(display); }
                Component getComponentName() { return new Component("app/.Host"); }
            }
            static class Host extends Activity implements HostedWindowPresentation.ContentHost {
                boolean desktop=true; Surface surface=new Surface();
                public boolean wholeDesktopViewer() { return desktop; }
                public Surface hostedSurface() { return surface; }
            }
            static class AppProfile {
                static AppProfile current(Activity a) { return new AppProfile(); }
                boolean owns(int id) { return id==0; }
            }
            static class FrameworkTaskSnapshot {
                int taskId=10,displayId=4,userId; boolean focused=true,visible=true;
                String packageName="app",topActivityName="app/.Host";
            }
            static class ShellAccess {
                static int reads; static boolean fail;
                static FrameworkTaskSnapshot task=new FrameworkTaskSnapshot();
                static FrameworkTaskSnapshot[] readTaskSnapshots(int display,int limit) throws IOException {
                    reads++; check(display==4,"queried another display");
                    if(fail) throw new IOException("unavailable");
                    return new FrameworkTaskSnapshot[]{task};
                }
                static String usefulMessage(Exception e) { return e.getMessage(); }
            }
            static class CompatibilityDiagnostics { static void record(String a,String b,String c,Exception e) {} }
            static List<java.lang.ref.WeakReference<Activity>> WINDOWS=new ArrayList<>();
            """ + RuntimeSourceFixture.methods("BuiltInWindowRegistry", "hostedKeyboardShortcuts") + """
            public static void verify() {
                check(hostedKeyboardShortcuts(4)==null && ShellAccess.reads==0,"no host queried framework");
                Host host=new Host(); WINDOWS.add(new java.lang.ref.WeakReference<>(host));
                check(hostedKeyboardShortcuts(4)==host.surface.target,"selected display's host not admitted");
                ShellAccess.task.taskId=11; check(hostedKeyboardShortcuts(4)==null,"background host admitted");
                ShellAccess.task.taskId=10; ShellAccess.task.userId=10;
                check(hostedKeyboardShortcuts(4)==null,"another profile admitted"); ShellAccess.task.userId=0;
                ShellAccess.task.focused=false; check(hostedKeyboardShortcuts(4)==null,"unknown focus guessed");
                ShellAccess.task.focused=true; ShellAccess.task.topActivityName="permission/.Dialog";
                check(hostedKeyboardShortcuts(4)==null,"permission activity bypassed");
                ShellAccess.task.topActivityName="app/.Host"; ShellAccess.task.visible=false;
                check(hostedKeyboardShortcuts(4)==null,"invisible host admitted"); ShellAccess.task.visible=true;
                host.desktop=false; int reads=ShellAccess.reads;
                check(hostedKeyboardShortcuts(4)==null && ShellAccess.reads==reads,"individual client acquired keyboard");
                host.desktop=true; host.display=0;
                check(hostedKeyboardShortcuts(4)==null && ShellAccess.reads==reads,"phone host selected");
                host.display=4; host.finishing=true;
                check(hostedKeyboardShortcuts(4)==null,"closing host admitted"); host.finishing=false;
                ShellAccess.fail=true; check(hostedKeyboardShortcuts(4)==null,"failure guessed focus");
            }
            """);
    }

    @Test public void outputFocusAndKeyboardRevocationCancelTheCapturedStream() throws Exception {
        RuntimeSourceFixture.verify("""
            static class KeyEvent {
                static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,KEYCODE_FORWARD=125;
                int key,scan,action,source;
                KeyEvent(int key,int scan,int action) { this.key=key; this.scan=scan; this.action=action; }
                KeyEvent(KeyEvent e) { this(e.key,e.scan,e.action); }
                int getKeyCode() { return key; } int getScanCode() { return scan; } int getAction() { return action; }
                boolean isFromSource(int mask) { return (source & mask)==mask; }
            }
            static class InputDevice { static final int SOURCE_MOUSE=0x2002, SOURCE_MOUSE_RELATIVE=0x20004; }
            static class HostedPointerInput {
            """ + RuntimeSourceFixture.methods("HostedPointerInput", "navigationKey") + """
            }
            static class HostedKeyboardShortcuts {
                interface Target { boolean key(KeyEvent event); void release(); }
            }
            static class SparseIntArray {
                TreeMap<Integer,Integer> values=new TreeMap<>();
                void put(int k,int v) { values.put(k,v); } void delete(int k) { values.remove(k); }
                int size() { return values.size(); }
                int keyAt(int i) { return new ArrayList<>(values.keySet()).get(i); }
                int valueAt(int i) { return new ArrayList<>(values.values()).get(i); }
                void clear() { values.clear(); }
            }
            static class Output {
                List<String> events=new ArrayList<>();
                void key(int k,int s,boolean down) { events.add(k+":"+s+(down?"+":"-")); }
            }
            SparseIntArray keys=new SparseIntArray(); Output output=new Output();
            long keyboardGeneration; boolean inputAllowed=true,keyboardAllowed=true,shown=true,attached=true,focused=true;
            boolean isAttachedToWindow() { return attached; } boolean isShown() { return shown; } boolean isFocused() { return focused; }
            """ + RuntimeSourceFixture.topLevelMethods("HostedSurfaceView", "keyboardShortcuts", "sendKey", "releaseHeldKeys", "mouseNavigationButton") + """
            public static void verify() {
                Fixture view=new Fixture(); var first=view.keyboardShortcuts();
                for (int source : new int[]{InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE_RELATIVE}) {
                    for (int code : new int[]{4,125}) for (int action : new int[]{0,1}) {
                        var mouse=new KeyEvent(code,0,action); mouse.source=source;
                        check(first.key(mouse), "mouse navigation duplicate not consumed");
                    }
                }
                first.release();
                check(view.output.events.isEmpty(), "mouse navigation leaked into the keyboard stream");
                KeyEvent alt=new KeyEvent(57,56,0);
                check(first.key(alt),"Alt rejected"); alt.key=99; // Accessibility owns and recycles its event.
                first.release(); first.release();
                check(view.output.events.equals(List.of("57:56+","57:56-")),"release retained borrowed event or repeated");
                first=view.keyboardShortcuts(); check(first.key(new KeyEvent(57,56,0)),"next chord rejected");
                view.releaseHeldKeys(); Output old=view.output; view.output=new Output();
                check(!first.key(new KeyEvent(61,15,0)),"revoked chord entered new output"); first.release();
                check(view.output.events.isEmpty() && old.events.size()==4,"old lease modified replacement");
                var current=view.keyboardShortcuts(); check(current.key(new KeyEvent(113,29,0)),"current chord rejected");
                view.shown=false; check(!current.key(new KeyEvent(61,15,0)),"hidden surface accepted key");
                current.release(); check(view.output.events.equals(List.of("113:29+","113:29-")),"hidden surface retained Ctrl");
                check(view.keyboardShortcuts()==null,"hidden surface acquired keyboard"); view.shown=true;
                view.keyboardAllowed=false; check(view.keyboardShortcuts()==null,"disabled keyboard acquired");
                view.keyboardAllowed=true; view.attached=false; check(view.keyboardShortcuts()==null,"detached surface acquired");
                view.attached=true; view.focused=false; check(view.keyboardShortcuts()==null,"unfocused view acquired");
            }
            """);
    }
}
