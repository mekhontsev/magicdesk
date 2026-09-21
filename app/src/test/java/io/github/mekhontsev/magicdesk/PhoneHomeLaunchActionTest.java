package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PhoneHomeLaunchActionTest {
    @Test
    public void homeOnlyRevealsChromeAndExplicitLaunchActionsRemainSeparate() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String EXTRA_ACTION = "action", ACTION_SHOW_START = "start",
                        ACTION_RESTORE_WINDOWS = "restore";
                static class Intent {
                    static final String ACTION_MAIN = "main", CATEGORY_HOME = "home";
                    String action = ACTION_MAIN, category = CATEGORY_HOME, extra;
                    String getAction() { return action; }
                    boolean hasCategory(String value) { return value.equals(category); }
                    String getStringExtra(String key) { return extra; }
                    void removeExtra(String key) { extra = null; }
                }
                static class Target {
                    boolean phone = true;
                    boolean isDefaultWorkspace() { return phone; }
                }
                Target mDisplayTarget = new Target();
                int reveals, starts, restores, captures;
                void revealTaskbar() { reveals++; }
                void captureInteractionStackForPanel() { captures++; }
                void setStartMenuVisible(boolean visible) { check(visible, "hid Start"); starts++; }
                void restoreLastVisibleWindows() { restores++; }
                public static void verify() {
                    Fixture f = new Fixture();
                    Intent home = new Intent();
                    f.handleLaunchAction(home);
                    f.handleLaunchAction(home);
                    check(f.reveals == 2 && f.starts == 0 && f.restores == 0 && f.captures == 0,
                            "Home opened a panel or restored windows");
                    home.extra = ACTION_SHOW_START;
                    f.handleLaunchAction(home);
                    check(f.starts == 1 && f.captures == 1 && f.reveals == 2 && home.extra == null,
                            "explicit Start changed");
                    home.extra = ACTION_RESTORE_WINDOWS;
                    f.handleLaunchAction(home);
                    check(f.restores == 1 && f.reveals == 2, "explicit restore changed");
                    f.mDisplayTarget.phone = false;
                    f.handleLaunchAction(home);
                    f.mDisplayTarget.phone = true;
                    home.category = "secondary_home";
                    f.handleLaunchAction(home);
                    home.category = Intent.CATEGORY_HOME;
                    home.action = "view";
                    f.handleLaunchAction(home);
                    f.handleLaunchAction(null);
                    check(f.reveals == 2, "unrelated launch revealed phone taskbar");
                }
                """ + RuntimeSourceFixture.methods("DesktopShellActivity",
                        "handleLaunchAction", "isPhoneDesktopHomeIntent"));
    }
}
