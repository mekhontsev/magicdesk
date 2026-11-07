package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ConsoleToolbarTest {
    @Test public void readySessionsShowIdentityOnlyOnTheSessionButton() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    var view = new Console();
                    view.mBackend = DesktopExecBackend.TERMUX;
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.GONE, "persistent Termux label");
                    check(view.mSessions.tooltip.equals("Sessions\\nTermux"), "missing Termux identity");
                    view.mBackend = DesktopExecBackend.APP;
                    view.mSnapshot = null;
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.GONE, "app console requires Shizuku");
                    check(view.mSessions.tooltip.equals("Sessions\\nMagicDesk"), "missing app identity");
                    view.mBackend = DesktopExecBackend.SHELL;
                    view.mSnapshot = new Snapshot(true, 2000, "");
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.GONE, "persistent shell label");
                    check(view.mSessions.tooltip.equals("Sessions\\nShell UID 2000"), "missing shell UID");
                    check(view.mSessions.color == UiColor.TEXT, "shell marked as root");
                    view.mSnapshot = new Snapshot(true, 0, "");
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.GONE, "persistent root label");
                    check(view.mSessions.tooltip.equals("Sessions\\nRoot UID 0"), "missing root UID");
                    check(view.mSessions.color == UiColor.ATTENTION, "root not distinguished");
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
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.VISIBLE, "startup hidden");
                    check(view.mShellStatus.text.equals("Starting terminal"), "startup includes backend label");
                    view.mTerminalStatus = "Access denied";
                    view.mTerminalFailed = true;
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.text.equals("Access denied"), "failure hidden");
                    check(view.mShellStatus.color == UiColor.ATTENTION, "failure not distinguished");
                    view.mTerminalFailed = false;
                    view.mTerminalStatus = "";
                    view.mBackend = DesktopExecBackend.SHELL;
                    view.mSnapshot = new Snapshot(false, -1, "Permission required");
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.VISIBLE, "access failure hidden");
                    check(view.mShellStatus.text.equals("Unavailable: Permission required"), "access reason lost");
                    view.mSnapshot = new Snapshot(true, 2000, "");
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.visibility == View.GONE, "recovered session kept stale failure");
                    view.mSnapshot = null;
                    view.updateStatus(view.mBackend,view.mSnapshot,view.mTerminalStatus,view.mTerminalFailed);
                    check(view.mShellStatus.text.equals("Unavailable: unavailable"), "unknown service failed");
                    view.updateStatus(null, null, "", false);
                    check(view.mShellStatus.visibility == View.GONE, "empty window requires shell access");
                    check(view.mSessions.description.equals("Sessions"), "empty window kept old backend identity");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                enum DesktopExecBackend { SHELL, TERMUX, APP }
                enum UiColor { ATTENTION, TEXT, MUTED }
                static class UiAppearance {
                    static void imageStates(View view, UiColor role) { view.tint=role; }
                    static void text(View view, UiColor role) { view.color=role; }
                }
                static class ShellAccess { static final int ROOT_UID = 0; static class Snapshot {
                    boolean ready; int uid; String error;
                    Snapshot(boolean ready,int uid,String error) { this.ready=ready;this.uid=uid;this.error=error; }
                    boolean isReady() { return ready; }
                } }
                static class Snapshot extends ShellAccess.Snapshot { Snapshot(boolean r,int u,String e) { super(r,u,e); } }
                static class R { static class string {
                    static final String terminal_sessions="Sessions", console_shell_termux="Termux",
                        console_title="Shell", console_app_title="MagicDesk", console_shell_root="Root UID %d", console_shell_android="Shell UID %d",
                        console_shell_unavailable="Unavailable: %s", state_unavailable="unavailable";
                } }
                static class View {
                    static final int VISIBLE=0, GONE=8;
                    String text, tooltip, description; int visibility; UiColor color, tint;
                    void setText(String value) { text=value; }
                    void setVisibility(int value) { visibility=value; }
                    void setTooltipText(String value) { tooltip=value; }
                    void setContentDescription(String value) { description=value; }
                }
                static class Console {
                    final Console mActivity=this;
                    View mShellStatus=new View(), mSessions=new View();
                    DesktopExecBackend mBackend;
                    Snapshot mSnapshot;
                    String mTerminalStatus="";
                    boolean mTerminalFailed;
                    String getString(String template, Object... args) { return String.format(template, args); }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalWindow", "updateStatus") + "}";
    }
}
