package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps notification entry points outside Android's trampoline restriction. */
public final class RuntimeNotificationContractTest {
    @Test
    public void userMessagesAreListedButInternalStatusIsNot() {
        assertTrue(DesktopNotificationMapper.includeNotification("magicdesk", "other", "status"));
        assertTrue(DesktopNotificationMapper.includeNotification(
                "magicdesk", "magicdesk", UserInteractions.CHANNEL));
        assertTrue(DesktopNotificationMapper.includeNotification(
                "magicdesk", "magicdesk", TerminalNotifications.CHANNEL));
        assertFalse(DesktopNotificationMapper.includeNotification("magicdesk", "magicdesk", "status"));
        assertFalse(DesktopNotificationMapper.includeNotification("magicdesk", "magicdesk", null));
    }

    @Test
    public void notificationEntryPointsLaunchActivitiesDirectly() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "MagicDeskRuntimeService.java"));

        assertTrue(source.contains("PendingIntent.getActivity("));
        for (String action : new String[]{"PANEL", "CONSOLE", "BLACK_SCREEN", "TOUCHPAD"}) {
            assertTrue(source.contains("ControlActivity.createLaunchIntent(this, ControlActivity.LaunchAction." + action + ")"));
        }
        final String notification = RuntimeSourceFixture.methods("MagicDeskRuntimeService", "buildNotification");
        assertFalse(notification.contains("ShellAccess"));
        assertFalse(notification.contains("ConsoleTerminalRegistry"));
        assertFalse(notification.contains("MagicDeskTouchpadActivity"));
        assertTrue(notification.contains(".setOnlyAlertOnce(true)"));
        assertTrue(notification.contains(".setStyle(new Notification.DecoratedCustomViewStyle())"));
        assertTrue(notification.contains(".setCustomContentView(content)"));
        assertTrue(notification.contains(".setCustomBigContentView(content)"));
        assertFalse(notification.contains(".addAction("));
        assertFalse(notification.contains("MediaStyle"));
        assertTrue(source.contains("options.setLaunchDisplayId("));
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertFalse(source.contains("PendingIntent.getForegroundService("));
        assertFalse(source.contains("PendingIntent.getService("));
        assertFalse(source.contains("PendingIntent.getBroadcast("));
        final String panel = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/ControlActivity.java"));
        assertTrue(panel.contains("createLaunchIntent(context).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)"));
        assertTrue(panel.contains(".setAction(BuildConfig.APPLICATION_ID + \".control.\" + action.name())"));
        final String manifest = Files.readString(Path.of("src/main/AndroidManifest.xml"));
        final String panelDeclaration = manifest.substring(manifest.indexOf("android:name=\".ControlActivity\""));
        assertTrue(panelDeclaration.substring(0, panelDeclaration.indexOf('>'))
                .contains("android:taskAffinity=\"${applicationId}.control\""));
    }

    @Test public void compactNotificationHasThreeIconsAndClickablePanelText() throws Exception {
        final var xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("src/main/res/layout/runtime_notification.xml").toFile());
        final var buttons = xml.getElementsByTagName("ImageButton");
        org.junit.Assert.assertEquals(3, buttons.getLength());
        final String[] ids = {"notification_console", "notification_black_screen", "notification_touchpad"};
        final String[] icons = {"ic_file_console", "ic_phone_screen_off", "ic_touchpad"};
        final String notification = RuntimeSourceFixture.methods("MagicDeskRuntimeService", "buildNotification");
        for (int i = 0; i < ids.length; i++) {
            final var button = (org.w3c.dom.Element) buttons.item(i);
            org.junit.Assert.assertEquals("@+id/" + ids[i], button.getAttribute("android:id"));
            org.junit.Assert.assertEquals("@drawable/" + icons[i], button.getAttribute("android:src"));
            org.junit.Assert.assertEquals("48dp", button.getAttribute("android:layout_width"));
            org.junit.Assert.assertEquals("48dp", button.getAttribute("android:layout_height"));
            assertTrue(button.getAttribute("android:contentDescription").startsWith("@string/"));
            assertTrue(notification.contains("setOnClickPendingIntent(R.id." + ids[i] + ","));
        }
        org.junit.Assert.assertEquals("gone", ((org.w3c.dom.Element) buttons.item(2)).getAttribute("android:visibility"));
        assertTrue(notification.contains("PhoneTouchpadController.canOpen() ? android.view.View.VISIBLE : android.view.View.GONE"));
        final var text = (org.w3c.dom.Element) xml.getElementsByTagName("LinearLayout").item(1);
        org.junit.Assert.assertEquals("@+id/notification_panel", text.getAttribute("android:id"));
        org.junit.Assert.assertEquals("1", text.getAttribute("android:layout_weight"));
        org.junit.Assert.assertEquals("true", text.getAttribute("android:screenReaderFocusable"));
        org.junit.Assert.assertEquals(2, text.getElementsByTagName("TextView").getLength());
        assertTrue(notification.contains("setOnClickPendingIntent(R.id.notification_panel, openControlPanelPendingIntent)"));
        assertTrue(notification.contains(".setContentIntent(openControlPanelPendingIntent)"));
    }

    @Test public void customContentHasAddressableDimensionsAndUsesPhoneResources() throws Exception {
        final var xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("src/main/res/layout/runtime_notification.xml").toFile());
        final var views = xml.getElementsByTagName("*");
        for (int i = 0; i < views.getLength(); i++) {
            final var view = (org.w3c.dom.Element) views.item(i);
            assertTrue(view.getTagName(), view.getAttribute("android:id").startsWith("@+id/"));
        }
        assertTrue(RuntimeSourceFixture.methods("MagicDeskRuntimeService", "buildNotification")
                .contains("RuntimeNotificationContent.create(this)"));
        final String create = RuntimeSourceFixture.methods("RuntimeNotificationContent", "create");
        assertTrue(create.contains("displays.getDisplay(Display.DEFAULT_DISPLAY)"));
        assertTrue(create.contains("context.createDisplayContext(phone)"));
        assertFalse(create.contains("Desktop"));
        assertFalse(create.contains("ShellAccess"));
    }

    @Test public void remoteDimensionsPreservePhoneSizesAndRelativeLayout() throws Exception {
        RuntimeSourceFixture.verify("""
                static class TypedValue { static final int COMPLEX_UNIT_PX = 0; }
                static class Configuration {
                    int direction;
                    int getLayoutDirection() { return direction; }
                }
                static class Resources {
                    final Configuration config = new Configuration();
                    Configuration getConfiguration() { return config; }
                }
                static class View {
                    static final int NO_ID = -1, LAYOUT_DIRECTION_RTL = 1;
                    final int id;
                    final Resources resources = new Resources();
                    ViewGroup.LayoutParams params;
                    int start, end, top, bottom;
                    View(int id, int width, int height) {
                        this.id = id; params = new ViewGroup.LayoutParams(width, height);
                    }
                    int getId() { return id; }
                    ViewGroup.LayoutParams getLayoutParams() { return params; }
                    Resources getResources() { return resources; }
                    int getPaddingStart() { return start; }
                    int getPaddingEnd() { return end; }
                    int getPaddingTop() { return top; }
                    int getPaddingBottom() { return bottom; }
                }
                static class ViewGroup extends View {
                    static class LayoutParams {
                        int width, height;
                        LayoutParams(int w, int h) { width=w; height=h; }
                    }
                    final java.util.List<View> children = new java.util.ArrayList<>();
                    ViewGroup(int id, int w, int h) { super(id,w,h); }
                    int getChildCount() { return children.size(); }
                    View getChildAt(int i) { return children.get(i); }
                }
                static class TextView extends View {
                    float size;
                    TextView(int id, float size) { super(id,-1,-2); this.size=size; }
                    float getTextSize() { return size; }
                }
                static class RemoteViews {
                    final java.util.Map<String, Float> values = new java.util.HashMap<>();
                    void setViewLayoutWidth(int id, float v, int u) { put(id,"width",v,u); }
                    void setViewLayoutHeight(int id, float v, int u) { put(id,"height",v,u); }
                    void setTextViewTextSize(int id, int u, float v) { put(id,"text",v,u); }
                    void put(int id, String k, float v, int u) {
                        check(u==TypedValue.COMPLEX_UNIT_PX,"unresolved dimension");
                        values.put(id+":"+k,v);
                    }
                    void setViewPadding(int id, int l, int t, int r, int b) {
                        put(id,"left",l,0); put(id,"top",t,0);
                        put(id,"right",r,0); put(id,"bottom",b,0);
                    }
                }
                public static void verify() {
                    var root = new ViewGroup(1,-1,156);
                    var weighted = new ViewGroup(2,0,-1); weighted.end=26;
                    var title = new TextView(3,52.5f);
                    weighted.children.add(title); root.children.add(weighted);
                    var button = new View(4,156,156); button.start=button.end=39;
                    button.top=button.bottom=39; root.children.add(button);
                    for (int direction : new int[]{0,1}) {
                        weighted.resources.config.direction=direction;
                        var content = new RemoteViews(); copyDimensions(root,content);
                        check(content.values.get("1:height")==156,"root size lost");
                        check(!content.values.containsKey("1:width"),"match parent frozen");
                        check(!content.values.containsKey("2:width"),"weight frozen");
                        check(!content.values.containsKey("2:height"),"match height frozen");
                        check(!content.values.containsKey("3:height"),"wrap content frozen");
                        check(content.values.get("3:text")==52.5f,"font scale lost");
                        check(content.values.get("4:width")==156,"button width lost");
                        check(content.values.get("4:height")==156,"button height lost");
                        check(content.values.get("4:left")==39,"icon padding lost");
                        check(content.values.get("2:left")== (direction==1 ? 26 : 0),"RTL left");
                        check(content.values.get("2:right")== (direction==1 ? 0 : 26),"RTL right");
                    }
                }
                """ + RuntimeSourceFixture.methods("RuntimeNotificationContent", "copyDimensions"));
    }

    @Test
    public void terminalMessagesShareSessionActivationAndRejectPreviousProcesses() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/TerminalNotificationActivity.java"));
        assertTrue(source.contains("TerminalNotifications.PROCESS.equals("));
        assertTrue(source.contains("ConsoleTerminalRegistry.status(id)"));
        assertFalse(source.contains("resumeIntent"));
        assertTrue(source.contains("TerminalSessions.open("));
        assertTrue(source.contains("CommandConsoleActivity.attachIntent("));
        assertFalse(source.contains("CommandConsoleActivity.createIntent("));
        assertFalse(source.contains("ToolApplications.open("));

        final String activity = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/CommandConsoleActivity.java"));
        assertTrue(activity.contains("if (hasFocus) { ConsoleTerminalRegistry.focused(mTerminalRegistryId, this); }"));
    }

    @Test public void panelActionsRequireFocusedResumedPhoneAndAreConsumedBeforeExecution() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String EXTRA_PANEL_ACTION="panelAction";
                enum LaunchAction { PANEL, CONSOLE, BLACK_SCREEN, TOUCHPAD }
                static class Intent {
                    String action;
                    String getStringExtra(String key) { return action; }
                    void removeExtra(String key) { action=null; }
                    void putExtra(String key,String value) { action=value; }
                }
                static class Display {
                    static final int DEFAULT_DISPLAY=0;
                    int id; int getDisplayId(){return id;}
                }
                static class KeyguardManager {
                    boolean locked; boolean isKeyguardLocked(){return locked;}
                }
                static class BlackScreenMode {
                    boolean active; void exit(){active=false;}
                }
                static class TerminalSessions {
                    static int opens;
                    static void resumeMostRecent(Fixture activity) {
                        check(activity.intent.action==null,"action still armed during console launch");
                        opens++;
                        activity.applyLaunchAction();
                    }
                }
                static CompletableFuture<Boolean> sBlackScreenRequest;
                final Intent intent=new Intent();
                final Display display=new Display();
                final KeyguardManager keyguard=new KeyguardManager();
                final BlackScreenMode mBlackScreen=new BlackScreenMode();
                boolean mResumed,focused,unavailable;
                int blackEnters, touchpadOpens;
                Intent getIntent(){return intent;}
                boolean hasWindowFocus(){return focused;}
                boolean isActivityUnavailable(){return unavailable;}
                Display getDisplay(){return display;}
                KeyguardManager getSystemService(Class<?> type){return keyguard;}
                void openBlackScreen(){check(intent.action==null,"action not consumed");mBlackScreen.active=true;blackEnters++;}
                void openTouchpad(){check(intent.action==null,"action not consumed");touchpadOpens++;applyLaunchAction();}
                public static void verify() {
                    var panel=new Fixture();panel.intent.action="CONSOLE";
                    panel.applyLaunchAction();
                    panel.mResumed=true;panel.applyLaunchAction();
                    check(TerminalSessions.opens==0,"acted before focus");
                    panel.focused=true;panel.display.id=7;panel.applyLaunchAction();
                    check(TerminalSessions.opens==0,"acted on external display");
                    panel.display.id=0;panel.keyguard.locked=true;panel.applyLaunchAction();
                    check(TerminalSessions.opens==0,"acted while locked");
                    panel.keyguard.locked=false;panel.applyLaunchAction();panel.applyLaunchAction();
                    check(TerminalSessions.opens==1,"console action repeated");
                    panel.intent.action="BLACK_SCREEN";panel.applyLaunchAction();
                    check(panel.mBlackScreen.active && panel.blackEnters==1,"black mode missing");
                    panel.mBlackScreen.exit();panel.applyLaunchAction();
                    check(!panel.mBlackScreen.active && panel.blackEnters==1,"focus reentered black mode");
                    sBlackScreenRequest=new CompletableFuture<>();var request=sBlackScreenRequest;
                    panel.mBlackScreen.active=true;panel.intent.action="PANEL";panel.applyLaunchAction();
                    check(!panel.mBlackScreen.active && request.isCompletedExceptionally(),"explicit panel did not supersede black mode");
                    panel.intent.action="TOUCHPAD";panel.focused=false;panel.applyLaunchAction();
                    check(panel.touchpadOpens==0 && panel.intent.action!=null,"touchpad bypassed foreground panel");
                    panel.focused=true;panel.applyLaunchAction();panel.applyLaunchAction();
                    check(panel.touchpadOpens==1,"touchpad action repeated");
                    panel.intent.action="CONSOLE";panel.mResumed=false;panel.applyLaunchAction();
                    check(TerminalSessions.opens==1 && panel.intent.action!=null,"consumed action while paused");
                    panel.mResumed=true;panel.unavailable=true;panel.applyLaunchAction();
                    check(TerminalSessions.opens==1,"acted after destruction");
                }
                """ + RuntimeSourceFixture.methods("ControlActivity", "applyLaunchAction")
                        .replace("android.app.KeyguardManager", "KeyguardManager"));
        final String save = RuntimeSourceFixture.methods("ControlActivity", "onSaveInstanceState");
        final String create = RuntimeSourceFixture.methods("ControlActivity", "onCreate");
        assertTrue(save.contains("state.putString(EXTRA_PANEL_ACTION, getIntent().getStringExtra(EXTRA_PANEL_ACTION))"));
        assertTrue(create.contains("savedInstanceState.getString(EXTRA_PANEL_ACTION)"));
        for (String method : new String[]{"onNewIntent", "onResume", "onWindowFocusChanged"}) {
            assertTrue(RuntimeSourceFixture.methods("ControlActivity", method).contains("applyLaunchAction()"));
        }
    }

    @Test public void touchpadAvailabilityIsSharedAndDoesNotRequireDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class ShellAccess { static boolean ready; static boolean isReady() { return ready; } }
                static class MagicDeskRuntime {
                    static int display = -1;
                    static boolean pointer;
                    static int inputDisplayId() { return display; }
                    static boolean isPointerTransportReady() { return pointer; }
                }
                public static void verify() {
                    for (int display : new int[] {-1, 0, 7}) {
                        for (boolean shell : new boolean[] {false, true}) {
                            for (boolean pointer : new boolean[] {false, true}) {
                                MagicDeskRuntime.display = display; ShellAccess.ready = shell;
                                MagicDeskRuntime.pointer = pointer;
                                check(canOpen() == (display > 0 && shell && pointer), "incorrect touchpad admission");
                            }
                        }
                    }
                }
                """ + RuntimeSourceFixture.methods("PhoneTouchpadController", "canOpen", "isSupported"));
        assertTrue(RuntimeSourceFixture.methods("PhoneControlPanelController", "render")
                .contains("mTouchpad.setEnabled(PhoneTouchpadController.canOpen())"));
        String open = RuntimeSourceFixture.methods("ControlActivity", "openTouchpad");
        assertTrue(open.indexOf("!PhoneTouchpadController.canOpen()") < open.indexOf("PhoneTouchpadController.open()"));
        assertTrue(RuntimeSourceFixture.methods("MagicDeskRuntimeService", "ensureInputRuntime")
                .contains("updateNotification()"));
    }
}
