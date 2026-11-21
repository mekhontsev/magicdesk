package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TmuxSessionProviderTest {
    @Test public void realServerRetainsFieldSeparatorsInCLocale() throws Exception {
        var available = new ProcessBuilder("sh", "-c", "command -v tmux >/dev/null").start();
        org.junit.Assume.assumeTrue("tmux not installed on build host", available.waitFor() == 0);
        var dir = java.nio.file.Files.createTempDirectory("md-tmux-");
        String socket = dir.resolve("server").toString();
        try {
            var command = new ProcessBuilder("sh", "-c", """
                    socket=$1
                    tmux() { command tmux -S "$socket" -f /dev/null "$@"; }
                    tmux new-session -d -s 'locale test' 'sleep 30' || exit 1
                    """ + TmuxSessionProvider.SESSIONS_COMMAND, "sh", socket).redirectErrorStream(true);
            command.environment().remove("TMUX");
            command.environment().put("LC_ALL", "C");
            var process = command.start();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            var list = TmuxSessionProvider.parse(output);
            assertTrue(list.complete);
            assertEquals("locale test", list.sessions.get(0).name);
        } finally {
            new ProcessBuilder("tmux", "-S", socket, "kill-server").start().waitFor();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(socket));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
    @Test public void guestDiscoveryUsesClientTtyNotHostPidOrSessionName() {
        var catalog = TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n"
                + "$0\tmain\t1\t1\t1234\nTTY\t/dev/pts/4\t$0\n__MAGICDESK_TMUX_COMPLETE__\n");
        assertEquals("main", catalog.find(catalog.terminals.get("/dev/pts/4")).name);
        assertTrue(catalog.clients.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TmuxSessionProvider.parse(
                "__MAGICDESK_TMUX_AVAILABLE__\nTTY\t/dev/pts/4\tbad-id\n"));
    }

    @Test public void guestOperationsRetainStoreExecutorAndCreationIdentity() {
        var session = new TmuxSessionProvider.Session("$1", "same name", 1, 0, 42);
        var attach = GuestTmux.Target.attach(session);
        assertEquals(attach, GuestTmux.Target.decode(attach.encode()));
        var create = GuestTmux.Target.create("team's work");
        assertEquals(create, GuestTmux.Target.decode(create.encode()));
        assertThrows(IllegalArgumentException.class, () -> GuestTmux.Target.decode(new String[]{"$1", "-1", "bad"}));
        for (var backend : DesktopExecBackend.values()) {
            var guest = new GuestEnvironmentCatalog.Entry("a", "id", "src", "/store with spaces", backend);
            String prefix = GuestEnvironmentCatalog.storeCommand(guest, "exec");
            assertTrue(GuestTmux.query(guest).startsWith(prefix + " '--network' 'none' "));
            assertTrue(attach.command(guest).startsWith(prefix + " '--' "));
            assertTrue(attach.command(guest).contains("#{session_created}"));
            assertTrue(create.command(guest).contains("new-session -s"));
        }
    }
    @Test public void explicitEndRecordsRequestBeforeExecutionAndDistinguishesFailure() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { }
                static class Session { String id="$1"; long createdSeconds=12; }
                record FilesKey(String packageName, int uid, String id, long created) { }
                static class ConsoleSessionFiles { boolean closed; void close() { closed=true; } }
                static class TermuxIntegration {
                    static class Endpoint { String packageName="com.termux"; int uid=123; }
                    static Endpoint inspect(Context context) { return new Endpoint(); }
                }
                static class TmuxSessionProvider { }
                static class ShellCommandLine { static String quote(String text) { return "'"+text+"'"; } }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class DesktopAutomationEventJournal {
                    static List<String> events=new ArrayList<>();
                    static void record(String type,String op,boolean ok,String detail) { events.add(op+":"+ok+":"+detail); }
                }
                static Map<FilesKey,ConsoleSessionFiles> FILES=new HashMap<>();
                static Throwable failure;
                static String sessionCommand(Session session,String command) { return command; }
                static void run(Context context,String command,java.util.function.Consumer<Throwable> callback) {
                    check(DesktopAutomationEventJournal.events.get(0).equals(
                            "end-requested:true:tmuxSessionId=$1 createdSeconds=12"), "request logged after execution");
                    check(command.equals("tmux kill-session -t '$1'"), "wrong tmux session selected");
                    callback.accept(failure);
                }
                """ + RuntimeSourceFixture.methods("TmuxSessionProvider", "end") + """
                public static void verify() {
                    var files=new ConsoleSessionFiles();
                    FILES.put(new FilesKey("com.termux",123,"$1",12),files);
                    failure=new IOException("test refusal");
                    end(new Context(),new Session(),error -> check(error==failure,"failure swallowed"));
                    check(!files.closed && FILES.size()==1,"failed end removed session files");
                    check(DesktopAutomationEventJournal.events.get(1).equals(
                            "end-result:false:tmuxSessionId=$1 createdSeconds=12 error=test refusal"), "failed result not recorded");
                    DesktopAutomationEventJournal.events.clear();failure=null;
                    end(new Context(),new Session(),error -> check(error==null,"successful end failed"));
                    check(files.closed && FILES.isEmpty(),"successful end did not release files");
                    check(DesktopAutomationEventJournal.events.get(1).equals(
                            "end-result:true:tmuxSessionId=$1 createdSeconds=12"), "successful result not recorded");
                }
                """);
    }

    @Test public void onlySuccessfulDiscoveryCanRetireSessionFiles() {
        assertFalse(TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n").complete);
        assertFalse(TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n$0\twork\t1\t0\t1\n").complete);
        assertTrue(TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n__MAGICDESK_TMUX_COMPLETE__\n").complete);
    }
    @Test public void paneRootsAreDeduplicatedAndOwnedBySession() {
        final var snapshot = TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n"
                + "$0\twork\t2\t1\t1234\n$1\tother\t1\t0\t1235\n"
                + "PANE\t42\t$0\nPANE\t42\t$0\nPANE\t43\t$0\nPANE\t44\t$1\n");
        assertEquals(java.util.Set.of(42,43), snapshot.panes.get("$0"));
        assertEquals(java.util.Set.of(44), snapshot.panes.get("$1"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.panes.get("$0").add(100));
        assertThrows(IllegalArgumentException.class, () -> TmuxSessionProvider.parse(
                "__MAGICDESK_TMUX_AVAILABLE__\nPANE\t0\t$0\n"));
    }
    @Test
    public void unavailableTmuxIsAValidEmptySnapshot() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_UNAVAILABLE__\n");

        assertFalse(snapshot.available);
        assertTrue(snapshot.sessions.isEmpty());
        assertEquals("tmux is not installed", snapshot.detail);
    }

    @Test
    public void parsesAvailableSessionsAndAttachmentState() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n"
                                + "$0\twork\t2\t1\t1234\n"
                                + "$3\tserver logs\t1\t0\t5678\n"
                                + "CLIENT\t42\t$0\n");

        assertTrue(snapshot.available);
        assertEquals(2, snapshot.sessions.size());
        assertEquals("work", snapshot.sessions.get(0).name);
        assertEquals(2, snapshot.sessions.get(0).windows);
        assertTrue(snapshot.sessions.get(0).attached());
        assertEquals("$3", snapshot.sessions.get(1).id);
        assertFalse(snapshot.sessions.get(1).attached());
        assertEquals(5678L, snapshot.sessions.get(1).createdSeconds);
        assertEquals("$0", snapshot.clients.get(42L));
    }

    @Test
    public void availableTmuxMayHaveNoServerOrSessions() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n");

        assertTrue(snapshot.available);
        assertTrue(snapshot.sessions.isEmpty());
    }

    @Test
    public void rejectsMalformedSessionRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n"
                                + "work\twork\t1\t0\t1\n"));
    }

    @Test
    public void attachAdvertisesHyperlinksOnlyForItsClient() {
        assertEquals(
                "exec tmux -T hyperlinks attach-session -t '$12'",
                TmuxSessionProvider.attachCommand("$12"));
    }

    @Test
    public void createQuotesValidatedNamesWithoutChangingTerminalFeatures() {
        assertEquals(
                "tmux new-session -d -s 'team'\"'\"'s work'",
                TmuxSessionProvider.createCommand("team's work"));
    }

    @Test
    public void rejectsTmuxSeparatorsAndInvalidIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.createCommand("work.dev"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.createCommand("work:1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.attachCommand("work"));
    }

    @Test
    public void sessionActionsCheckCreationIdentityBeforeUsingRecycledId() {
        final var session = new TmuxSessionProvider.Session("$12", "work", 1, 0, 1234);
        assertEquals("test \"$(tmux display-message -p -t '$12' '#{session_created}' 2>/dev/null)\" = '1234'"
                        + " || { printf 'tmux session no longer exists\\n' >&2; exit 1; }\n"
                        + "exec tmux -T hyperlinks attach-session -t '$12'",
                TmuxSessionProvider.sessionCommand(session, TmuxSessionProvider.attachCommand(session.id)));
    }
}
