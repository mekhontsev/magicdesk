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
