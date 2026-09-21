package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RecentLaunchContractTest {
    @Test public void consoleHistoryKeepsBackendButNotSessionOrTransientCommand() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String EXTRA_AUTO_RUN_COMMAND = "command", EXTRA_TMUX_SESSION = "tmux";
                static class Context { String getString(int id) { return "title-" + id; } }
                static class R { static class string { static int console_title = 1, console_termux_title = 2; } }
                enum DesktopLaunchMode { AUTO }
                enum DesktopExecBackend { SHELL }
                static class Intent {
                    static final int URI_INTENT_SCHEME = 1;
                    boolean termux;
                    String command;
                    Set<String> extras = new HashSet<>();
                    boolean hasExtra(String key) { return extras.contains(key); }
                    String getStringExtra(String key) { return command; }
                    String toUri(int scheme) { return termux ? "termux-clean" : "shell-clean"; }
                }
                record DesktopApplicationShortcut(String name, String icon, String exec, Object target,
                        String intent, DesktopLaunchMode mode, boolean defaults, DesktopExecBackend backend, boolean terminal) { }
                static boolean isTermux(Intent intent) { return intent.termux; }
                static Intent createTermuxIntent(Context context) { Intent intent = new Intent(); intent.termux = true; return intent; }
                static Object launchTarget() { return "console"; }
                public static void verify() {
                    Context context = new Context();
                    Intent source = new Intent();
                    source.extras.add("terminal-id"); source.extras.add("directory");
                    var shell = recentLaunch(context, source);
                    check(shell.defaults() && shell.intent().isEmpty(), "shell remembered runtime extras");
                    source.termux = true;
                    var termux = recentLaunch(context, source);
                    check(!termux.defaults() && termux.intent().equals("termux-clean"), "Termux lost its backend");
                    check(!termux.name().equals(shell.name()), "terminal titles are indistinguishable");
                    source.extras.add(EXTRA_AUTO_RUN_COMMAND);
                    source.command = "";
                    check(recentLaunch(context, source).equals(termux), "empty automation command hid a terminal launch");
                    source.command = "test command";
                    check(recentLaunch(context, source) == null, "command recipe duplicated as generic terminal");
                    source.extras.add(EXTRA_TMUX_SESSION);
                    check(recentLaunch(context, source).equals(termux), "tmux attachment remembered ephemeral identity");
                }
                """ + RuntimeSourceFixture.methods("CommandConsoleActivity", "recentLaunch"));
    }

    @Test public void x11WaitsForUsabilityAndRecordsOnlyExplicitLaunchScope() throws Exception {
        RuntimeSourceFixture.verify("""
                enum State { STARTING, READY }
                enum RecentLaunchScope { DESKTOP, INDEPENDENT }
                static class RecentApplicationStore {
                    record Entry(String name) { Entry usedAt(long time) { return this; } }
                }
                static class RecentApplications {
                    static List<RecentLaunchScope> scopes = new ArrayList<>();
                    static RecentApplicationStore.Entry last;
                    static void record(Object c, RecentApplicationStore.Entry r, RecentLaunchScope scope) { scopes.add(scope); last = r; }
                }
                Object context;
                RecentApplicationStore.Entry recipe = new RecentApplicationStore.Entry("writer");
                Map<Long, RecentApplicationStore.Entry> windowRecipes = new HashMap<>();
                RecentLaunchScope recentScope;
                State state = State.STARTING;
                boolean application = true, hadWindows;
                public static void verify() {
                    Fixture f = new Fixture();
                    f.recordUse(RecentLaunchScope.INDEPENDENT);
                    f.state = State.READY; f.recordUse();
                    check(RecentApplications.scopes.isEmpty(), "unusable application entered history");
                    f.hadWindows = true; f.recordUse();
                    f.recordUse(RecentLaunchScope.DESKTOP);
                    check(RecentApplications.scopes.equals(List.of(RecentLaunchScope.INDEPENDENT, RecentLaunchScope.DESKTOP)),
                            "reuse did not record the new destination scope");
                    f = new Fixture(); f.application = false; f.state = State.READY;
                    f.recordUse();
                    check(RecentApplications.scopes.size() == 2, "unpresented manager session invented a scope");
                    f.recordUse(RecentLaunchScope.INDEPENDENT);
                    check(RecentApplications.scopes.size() == 3, "whole-desktop launch missing");
                    f.windowRecipes.put(42L, new RecentApplicationStore.Entry("calc"));
                    f.recordUse(42, RecentLaunchScope.INDEPENDENT);
                    check(RecentApplications.last.name().equals("calc"), "forwarded launch recorded original session's application");
                    f.state = State.STARTING;
                    f.recordUse(42, RecentLaunchScope.INDEPENDENT);
                    check(RecentApplications.scopes.size() == 4, "unready alias entered history");
                }
                """ + RuntimeSourceFixture.methods("X11Sessions", "recordUse"));
    }

    @Test public void recentAndRunningHaveDifferentSourcesRegardlessOfAccess() throws Exception {
        final String entries = RuntimeSourceFixture.methods("StartMenuContent", "entries");
        assertTrue(entries.contains("section == MENU_RUNNING"));
        assertTrue(entries.contains("RecentApplications.entries(mLaunchControls.recentScope())"));
        assertFalse(entries.contains("ShellAccess"));
        final String host = RuntimeSourceFixture.methods("FullscreenStartController", "runningEntries");
        assertFalse(host.contains("RecentApplications"));
        final String builtIn = RuntimeSourceFixture.methods("BuiltInRecentLaunch", "describe");
        assertTrue(builtIn.contains("BuiltInDesktopAppCatalog.searchEntries()"));
        assertFalse(builtIn.contains("source.toUri"));
        final String internal = RuntimeSourceFixture.methods("AppTaskController", "launchInternalWindow");
        assertTrue(internal.contains("result.hasObservedTask()"));
        assertTrue(internal.indexOf("launchIntent(") < internal.indexOf("RecentApplications.recordBuiltIn"));
    }
}
