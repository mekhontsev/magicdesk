package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ConsoleToolbarTest {
    @Test public void readySessionsShowIdentityOnlyOnTheSessionButton() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    var view = new Console();
                    view.mBackend = DesktopExecBackend.TERMUX;
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.GONE, "persistent Termux label");
                    check(view.mSessions.tooltip.equals("Sessions\\nTermux"), "missing Termux identity");
                    view.mBackend = DesktopExecBackend.SHELL;
                    view.mSnapshot = new Snapshot(true, 2000, "");
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.GONE, "persistent shell label");
                    check(view.mSessions.tooltip.equals("Sessions\\nShell UID 2000"), "missing shell UID");
                    check(view.mSessions.tint == Console.COLOR_TEXT, "shell marked as root");
                    view.mSnapshot = new Snapshot(true, 0, "");
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.GONE, "persistent root label");
                    check(view.mSessions.tooltip.equals("Sessions\\nRoot UID 0"), "missing root UID");
                    check(view.mSessions.tint == Console.COLOR_AMBER, "root not distinguished");
                    check(view.mSessions.tooltip.equals(view.mSessions.description), "inaccessible identity");
                }
                """);
    }

    @Test public void startupAndErrorsRemainVisibleEvenWithoutTheToolbarLabel() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    var view = new Console();
                    view.mBackend = DesktopExecBackend.TERMUX;
                    view.mTerminalStatus = "Starting terminal";
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.VISIBLE, "startup hidden");
                    check(view.mShellStatus.text.equals("Starting terminal"), "startup includes backend label");
                    view.mTerminalStatus = "Access denied";
                    view.mTerminalFailed = true;
                    view.updateShellStatus();
                    check(view.mShellStatus.text.equals("Access denied"), "failure hidden");
                    check(view.mShellStatus.color == Console.COLOR_AMBER, "failure not distinguished");
                    view.mTerminalFailed = false;
                    view.mTerminalStatus = "";
                    view.mBackend = DesktopExecBackend.SHELL;
                    view.mSnapshot = new Snapshot(false, -1, "Permission required");
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.VISIBLE, "access failure hidden");
                    check(view.mShellStatus.text.equals("Unavailable: Permission required"), "access reason lost");
                    view.mSnapshot = new Snapshot(true, 2000, "");
                    view.updateShellStatus();
                    check(view.mShellStatus.visibility == View.GONE, "recovered session kept stale failure");
                    view.mSnapshot = null;
                    view.updateShellStatus();
                    check(view.mShellStatus.text.equals("Unavailable: unavailable"), "unknown service failed");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                enum DesktopExecBackend { SHELL, TERMUX }
                static class ShellAccess { static final int ROOT_UID = 0; }
                record Snapshot(boolean ready, int uid, String error) { boolean isReady() { return ready; } }
                static class R { static class string {
                    static final String terminal_sessions="Sessions", console_shell_termux="Termux",
                        console_title="Shell", console_shell_root="Root UID %d", console_shell_android="Shell UID %d",
                        console_shell_unavailable="Unavailable: %s", state_unavailable="unavailable";
                } }
                static class View {
                    static final int VISIBLE=0, GONE=8;
                    String text, tooltip, description; int visibility, color, tint;
                    void setText(String value) { text=value; }
                    void setTextColor(int value) { color=value; }
                    void setVisibility(int value) { visibility=value; }
                    void setTooltipText(String value) { tooltip=value; }
                    void setContentDescription(String value) { description=value; }
                    void setImageTintList(int value) { tint=value; }
                }
                static class ColorStateList { static int valueOf(int value) { return value; } }
                static class Console {
                    static final int COLOR_AMBER=1, COLOR_TEXT=2, COLOR_MUTED=3;
                    View mShellStatus=new View(), mSessions=new View();
                    DesktopExecBackend mBackend;
                    Snapshot mSnapshot;
                    String mTerminalStatus="";
                    boolean mTerminalFailed;
                    String getString(String template, Object... args) { return String.format(template, args); }
                """ + RuntimeSourceFixture.methods("CommandConsoleActivity", "updateShellStatus") + "}";
    }
}
