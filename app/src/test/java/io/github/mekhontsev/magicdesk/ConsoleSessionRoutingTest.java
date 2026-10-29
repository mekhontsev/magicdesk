package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Actual Activity routing with retained PTYs and lifecycle doubles. */
public final class ConsoleSessionRoutingTest {
    @Test public void switchesWithoutReplayingCommandsAndUsesRetainedBackend() throws Exception {
        verify("""
                Window window = new Window();
                window.openSession(new Intent("a", DesktopExecBackend.SHELL, false, "first"));
                var a = window.mSession;
                window.openSession(new Intent("b", DesktopExecBackend.TERMUX, false, "second"));
                var b = window.mSession;
                check(a != b && b.startup.equals("second"), "new launch reused the selected PTY");
                window.openSession(new Intent("a", DesktopExecBackend.TERMUX, true, "must-not-run"));
                check(window.mSession == a && window.mBackend == DesktopExecBackend.SHELL,
                        "attach trusted incoming backend instead of retained identity");
                check(a.startup.equals("first") && a.starts == 2, "attach replayed startup command");
                check(window.intent.attach && window.intent.command.isEmpty(), "saved intent can replay startup");
                int registrations = ConsoleTerminalRegistry.registrations;
                window.openSession(new Intent("a", DesktopExecBackend.SHELL, true, "ignored"));
                check(ConsoleTerminalRegistry.registrations == registrations, "same-session intent revoked input");
                """);
    }

    @Test public void invalidAttachmentPreservesSelectedSession() throws Exception {
        verify("""
                Window window = new Window();
                window.openSession(new Intent("a", DesktopExecBackend.SHELL, false, ""));
                var original = window.mSession;
                window.openSession(new Intent("missing", DesktopExecBackend.SHELL, true, ""));
                check(window.mSession == original && window.intent.id.equals("a"), "failed selection lost current PTY");
                check(ConsoleTerminalRegistry.sessions.size() == 1, "expired attachment created a shell");
                """);
    }

    @Test public void recreationDoesNotReclaimTransferredOrEndedSessions() throws Exception {
        verify("""
                Window old = new Window();
                old.openSession(new Intent("a", DesktopExecBackend.SHELL, false, "once"));
                Intent saved = old.intent;
                Window recreated = new Window();
                recreated.restoreSession(saved);
                check(recreated.mSession == old.mSession, "configuration recreation lost PTY");
                Window other = new Window(); other.task = 2;
                other.openSession(saved);
                recreated.restoreSession(saved);
                check(recreated.mSession == null && recreated.intent.empty, "stale saved state stole transferred session");
                ConsoleTerminalRegistry.sessions.clear();
                recreated.restoreSession(saved);
                check(recreated.mSession == null && ConsoleTerminalRegistry.sessions.isEmpty(), "ended PTY was restarted");
                """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify("""
                enum DesktopExecBackend { SHELL, TERMUX }
                static final String EXTRA_TMUX_SESSION="tmux", EXTRA_TMUX_CREATED="created",
                        EXTRA_ATTACH_ONLY="attach", EXTRA_EMPTY="empty";
                interface Function<T,R> extends java.util.function.Function<T,R> {}
                static class Intent {
                    String id, command=""; DesktopExecBackend backend; boolean attach, empty;
                    Intent(Object context, Class<?> type) {}
                    Intent(String id, DesktopExecBackend backend, boolean attach, String command) {
                        this.id=id;this.backend=backend;this.attach=attach;this.command=command;
                    }
                    String getStringExtra(String key) { return null; }
                    long getLongExtra(String key,long value) { return value; }
                    boolean getBooleanExtra(String key,boolean value) { return key.equals(EXTRA_ATTACH_ONLY) ? attach : empty; }
                    Intent putExtra(String key,boolean value) { empty=value;return this; }
                }
                static class CommandConsoleActivity {}
                static class ShellAccess {
                    static class Snapshot {}
                    static Snapshot currentSnapshot() { return new Snapshot(); }
                    static String usefulMessage(Throwable e) { return e.getMessage(); }
                }
                static class TerminalTransport { interface Factory {} }
                static class ConsoleSessionFiles {
                    ConsoleSessionFiles(Object context, DesktopExecBackend backend) {}
                }
                static class ConsoleTerminalSession {
                    final DesktopExecBackend backend; final String startup; int starts;
                    ConsoleTerminalSession(String dir,int c,int r,int cw,int ch,DesktopExecBackend b,
                            String command,TerminalTransport.Factory f,Object listener) { backend=b;startup=command; }
                    DesktopExecBackend backend() { return backend; }
                    String title() { return ""; }
                    void setFiles(ConsoleSessionFiles files, boolean owned) {}
                }
                static class ConsoleTerminalRegistry {
                    record Snapshot(String id, int taskId, DesktopExecBackend backend) {}
                    static Map<String,ConsoleTerminalSession> sessions=new HashMap<>();
                    static Map<String,Integer> tasks=new HashMap<>(); static int registrations;
                    static ConsoleTerminalSession acquire(String id,Function<Object,ConsoleTerminalSession> factory) {
                        var result=sessions.get(id);
                        if(result==null) { if(factory==null) throw new IllegalStateException("expired");
                            result=factory.apply(null); sessions.put(id,result); }
                        return result;
                    }
                    static String nextId() { return "new"; }
                    static void bindTmux(String id,String tmux,long created) {}
                    static void register(Window window,ConsoleTerminalSession session,View view,String id) {
                        window.onTerminalDetached(); tasks.put(id,window.task); registrations++;
                    }
                    static Snapshot status(String id) {
                        var s=sessions.get(id);return s==null?null:new Snapshot(id,tasks.getOrDefault(id,-1),s.backend);
                    }
                    static void focused(String id,Window window) {}
                }
                static class TmuxSessionProvider {
                    static boolean isSessionId(String id) { return true; }
                    static ConsoleSessionFiles files(Object context, String id, long created) {
                        return new ConsoleSessionFiles(context, DesktopExecBackend.TERMUX);
                    }
                }
                static class MagicDeskRuntime { static void startTools(Window w,boolean shell) {} }
                static class Toast { static final int LENGTH_LONG=1;
                    static Toast makeText(Object w,String text,int duration) { return new Toast(); } void show() {} }
                static class View {
                    int columns(){return 80;} int rows(){return 24;} int cellWidth(){return 8;} int cellHeight(){return 16;}
                    void attach(ConsoleTerminalSession session,Object actions) {} void requestFocus() {}
                }
                static class Window {
                    View mTerminalView=new View(); Object mActions;
                    ConsoleTerminalSession mSession; String mTerminalRegistryId="",mTerminalStatus="";
                    DesktopExecBackend mBackend; ShellAccess.Snapshot mSnapshot;
                    boolean mTerminalFailed,mPermissionRequested; Intent intent; int task=1;
                    int getTaskId(){return task;} boolean isFinishing(){return false;} boolean isDestroyed(){return false;}
                    boolean hasWindowFocus(){return true;}
                    Object getApplicationContext(){return this;}
                    void setIntent(Intent value){intent=value;}
                    void onTitleChanged(String title) {} void onMetadataChanged() {} void updateShellStatus() {} void updateActions() {}
                    void maybeStartSession(){mSession.starts++;}
                    TerminalTransport.Factory terminalTransportFactory(DesktopExecBackend b, ConsoleSessionFiles files){return null;}
                    static String terminalId(Intent intent){return intent.id;}
                    static DesktopExecBackend backend(Intent intent){return intent.backend;}
                    static String initialDirectory(Intent intent){return "/";}
                    static String takeAutoRunCommand(Intent intent){String c=intent.command;intent.command="";return c;}
                    static Intent attachIntent(Window w,ConsoleTerminalRegistry.Snapshot session) {
                        return new Intent(session.id(),session.backend(),true,"");
                    }
                """ + RuntimeSourceFixture.methods("CommandConsoleActivity", "openSession", "restoreSession", "onTerminalDetached")
                + "}\n public static void verify() {" + body + "}");
    }
}
