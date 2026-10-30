package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RecentHistoryEditTest {
    @Test public void queuedEditsPublishBeforeCompletionAndReconcilePartialFailures() throws Exception {
        RuntimeSourceFixture.verify("""
                enum RecentLaunchScope { DESKTOP, INDEPENDENT }
                static class Queue {
                    final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
                    void execute(Runnable task) { tasks.add(task); }
                    void post(Runnable task) { tasks.add(task); }
                    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
                }
                static final Queue IO = new Queue(), MAIN = new Queue();
                static class Context { Context getApplicationContext() { return this; } }
                static class ShellAccess { static String usefulMessage(Throwable t) { return t.getMessage(); } }
                static class Log { static void w(String tag, String text, Throwable t) { } }
                static class RecentApplicationStore {
                    record Entry(String key) { }
                    List<Entry> values = List.of();
                    boolean fail;
                    List<Entry> read() throws IOException { return values; }
                    List<Entry> clear() throws IOException {
                        values = List.of();
                        if (fail) throw new IOException("partial clear failed");
                        return values;
                    }
                    List<Entry> remove(Entry entry) {
                        values = values.stream().filter(e -> !e.key().equals(entry.key())).toList();
                        return values;
                    }
                }
                static final Map<RecentLaunchScope, RecentApplicationStore> stores = new EnumMap<>(RecentLaunchScope.class);
                static RecentApplicationStore store(Context c, RecentLaunchScope s) { return stores.get(s); }
                static volatile Map<RecentLaunchScope, Snapshot> snapshots = Map.of();
                static final Set<java.util.function.Consumer<RecentLaunchScope>> listeners = new LinkedHashSet<>();
                public static void verify() throws Exception {
                    Context context = new Context();
                    var d = RecentLaunchScope.DESKTOP;
                    var i = RecentLaunchScope.INDEPENDENT;
                    var one = new RecentApplicationStore.Entry("one");
                    var two = new RecentApplicationStore.Entry("two");
                    for (var scope : RecentLaunchScope.values()) {
                        var history = new RecentApplicationStore();
                        history.values = List.of(one, two);
                        stores.put(scope, history);
                        publish(scope, history.read(), "");
                    }
                    List<String> calls = new ArrayList<>();
                    java.util.function.Consumer<RecentLaunchScope> first = scope -> calls.add("first:" + scope);
                    java.util.function.Consumer<RecentLaunchScope> second = scope -> calls.add("second:" + scope);
                    subscribe(first); subscribe(first); subscribe(second);
                    remove(context, d, one, failure -> {
                        check(failure == null && entries(d).equals(List.of(two)), "completion preceded snapshot");
                        calls.add("removed");
                    });
                    check(entries(d).size() == 2, "edit ran on UI thread");
                    IO.drain();
                    check(calls.isEmpty(), "UI callbacks ran on IO thread");
                    MAIN.drain();
                    check(calls.equals(List.of("first:DESKTOP", "second:DESKTOP", "removed")), "observers not delivered once before completion");
                    check(entries(i).equals(List.of(one, two)), "edit crossed history scopes");
                    calls.clear();
                    clear(context, d, failure -> { check(failure == null, "clear failed"); calls.add("clear"); });
                    edit(context, d, history -> { history.values = List.of(one); return history.values; }, failure -> calls.add("launch"));
                    IO.drain();
                    unsubscribe(second);
                    MAIN.drain();
                    check(entries(d).equals(List.of(one)), "queued later launch lost after clear");
                    check(calls.equals(List.of("first:DESKTOP", "clear", "first:DESKTOP", "launch")), "queue order or detached observer incorrect");
                    stores.get(d).fail = true;
                    clear(context, d, failure -> {
                        check(failure != null && failure.getMessage().contains("partial"), "failure hidden");
                        check(entries(d).isEmpty(), "partial edit left stale snapshot");
                        check(error(d).contains("partial"), "error state missing");
                    });
                    IO.drain(); MAIN.drain();
                    check(entries(i).equals(List.of(one, two)), "failure affected other history");
                }
                """ + RuntimeSourceFixture.nestedClass("RecentApplications", "Snapshot")
                        + RuntimeSourceFixture.nestedClass("RecentApplications", "Edit")
                        + RuntimeSourceFixture.methods("RecentApplications", "entries", "error", "publish",
                                "subscribe", "unsubscribe", "notifyChanged", "edit", "remove", "clear", "failed"));
    }

    @Test public void startKeepsExistingContextActionsAndCapturesSelectedScope() throws Exception {
        var context = RuntimeSourceFixture.methods("StartMenuContent", "bindContextMenu");
        assertTrue(context.contains("final RecentLaunchScope scope = mLaunchControls.recentScope()"));
        assertTrue(context.contains("mHost.appContext(view, entry, removeRecent)"));
        assertTrue(context.contains("action_remove_recent"));
        assertTrue(context.contains("action_delete_shortcut"));
        var confirmation = RuntimeSourceFixture.methods("StartMenuContent", "confirmClearRecent");
        assertTrue(confirmation.indexOf("final RecentLaunchScope scope") < confirmation.indexOf("setPositiveButton"));
        assertTrue(confirmation.contains("RecentApplications.clear(mActivity, scope"));
        assertTrue(RuntimeSourceFixture.methods("StartMenuContent", "prepare").contains("RecentApplications.subscribe"));
        assertTrue(RuntimeSourceFixture.methods("StartMenuContent", "pause").contains("RecentApplications.unsubscribe"));
        assertTrue(RuntimeSourceFixture.methods("StartMenuContent", "release").contains("RecentApplications.unsubscribe"));
        assertFalse(confirmation.contains("ShellAccess"));
    }
}
