package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayPresentationSelectionTest {
    @Test public void sourceGeometryChangesReuseTheBindingTransaction() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopDisplayInfo {
                    String uniqueId = "phone";
                    int width, height, densityDpi = 160;
                    DesktopDisplayInfo(int w, int h) { width = w; height = h; }
                }
                static class Session {
                    boolean closed;
                    Change change;
                    DesktopDisplayInfo source = new DesktopDisplayInfo(100, 200);
                }
                static class Change {
                    static int starts;
                    final Map<Session, DesktopDisplayInfo> next;
                    Change(Map<Session, DesktopDisplayInfo> next) { this.next = next; }
                    void start() { starts++; next.forEach((session, source) -> session.source = source); }
                }
                public static void verify() {
                    Session s = new Session();
                    updateGeometry(s, new DesktopDisplayInfo(100, 200));
                    check(Change.starts == 0, "unchanged geometry started a transaction");
                    updateGeometry(s, new DesktopDisplayInfo(200, 100));
                    check(Change.starts == 1 && s.source.width == 200, "rotation ignored");
                    DesktopDisplayInfo replaced = new DesktopDisplayInfo(300, 200);
                    replaced.uniqueId = "another display";
                    updateGeometry(s, replaced);
                    check(Change.starts == 1, "stale identity applied");
                    s.closed = true;
                    updateGeometry(s, new DesktopDisplayInfo(100, 200));
                    check(Change.starts == 1, "closed viewer rebound");
                }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "updateGeometry"));
    }

    @Test public void failedBindingRetrySerializesAndJoinsRepeatedRequests() throws Exception {
        RuntimeSourceFixture.verify("""
                interface Callback { void onComplete(Throwable error); }
                static class BuiltInWindowLauncher { interface Callback extends Fixture.Callback { } }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    void requirePresentationOutput(DesktopDisplayInfo output) { }
                }
                static class Session {
                    DesktopDisplayInfo source = new DesktopDisplayInfo(1);
                    DesktopDisplayInfo output = new DesktopDisplayInfo(3);
                    boolean closed, ready, visible = true, outputAttachment = true;
                    Object listener;
                    String error = "attach failed";
                    Change change;
                    List<BuiltInWindowLauncher.Callback> completions = new ArrayList<>();
                }
                static class Change {
                    static int starts;
                    final Map<Session, DesktopDisplayInfo> next;
                    Change(Map<Session, DesktopDisplayInfo> next) { this.next = next; }
                    void start() {
                        starts++;
                        next.keySet().forEach(s -> { s.change = this; s.error = ""; });
                    }
                }
                static class TaskCommandQueue { static void execute(Runnable r) { r.run(); } }
                static class Main { void post(Runnable r) { r.run(); } }
                static final Main MAIN = new Main();
                static class DesktopDisplayCatalog {
                    static DesktopDisplayInfo require(int id, String uniqueId) { return new DesktopDisplayInfo(id); }
                }
                static DesktopDisplayInfo requireSource(int id, String uniqueId) {
                    return new DesktopDisplayInfo(id);
                }
                static Session forSource(int id) {
                    check(current.outputAttachment, "mirror selection looked up an output attachment to exchange");
                    return current.source.id == id ? current : null;
                }
                static void reportSelection(Session session, Throwable error) { }
                static void notify(Session session) { }
                static Session current;
                public static void verify() {
                    current = new Session();
                    List<Throwable> results = new ArrayList<>();
                    select(current, 1, results::add);
                    check(Change.starts == 1 && current.change != null, "retry was not serialized");
                    check(current.change.next.get(current).id == 1, "retry changed source");
                    select(current, 1, results::add);
                    check(Change.starts == 1 && current.completions.size() == 2,
                            "duplicate retry did not join existing change");
                    check(results.isEmpty(), "acknowledged before attachment");
                    select(current, 2, results::add);
                    check(results.size() == 1 && results.get(0) != null,
                            "conflicting source change was accepted during retry");
                    current.change = null;
                    current.ready = true;
                    current.error = "previous selection rejected";
                    select(current, 1, results::add);
                    check(current.error.isEmpty() && results.get(1) == null,
                            "valid selection retained a previous error");
                    current.outputAttachment = false;
                    select(current, 2, results::add);
                    check(current.change.next.size() == 1 && current.change.next.get(current).id == 2,
                            "mirror selection exchanged another binding");
                }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "select"));
    }

    @Test public void pendingBindingsReserveSourcesAndParticipateInCycleValidation() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    final String source = "virtual";
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    void requirePresentationOutput(DesktopDisplayInfo output) { }
                }
                static class Session {
                    DesktopDisplayInfo source, output;
                    boolean closed, outputAttachment = true;
                    Change change;
                    Session(int source, int output) {
                        this.source = new DesktopDisplayInfo(source);
                        this.output = new DesktopDisplayInfo(output);
                    }
                }
                static class Change {
                    final Map<Session, DesktopDisplayInfo> next = new LinkedHashMap<>();
                }
                static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
                static void rejects(Runnable action) {
                    boolean rejected = false;
                    try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) {
                        rejected = true;
                    }
                    check(rejected, "invalid pending binding accepted");
                }
                public static void verify() {
                    Session a = new Session(1, 3);
                    a.change = new Change();
                    a.change.next.put(a, new DesktopDisplayInfo(2));
                    SESSIONS.put("a", a);
                    rejects(() -> validate(new DesktopDisplayInfo(2), new DesktopDisplayInfo(4), true));
                    check(!canAttachOutput(new DesktopDisplayInfo(2), new DesktopDisplayInfo(4)),
                            "portable selection ignored a pending source reservation");
                    check(canAttachOutput(new DesktopDisplayInfo(6), new DesktopDisplayInfo(7)),
                            "unused source/output pair was rejected");
                    rejects(() -> validate(new DesktopDisplayInfo(3), new DesktopDisplayInfo(2), true));
                    Session b = new Session(5, 4);
                    SESSIONS.put("b", b);
                    rejects(() -> validateGraph(Map.of(b, new DesktopDisplayInfo(2))));
                    validateGraph(Map.of(b, new DesktopDisplayInfo(1)));
                    validate(new DesktopDisplayInfo(2), new DesktopDisplayInfo(4), false);
                    b.outputAttachment = false;
                    validateGraph(Map.of(b, new DesktopDisplayInfo(2)));
                    rejects(() -> validate(new DesktopDisplayInfo(3), new DesktopDisplayInfo(2), false));
                }
                """ + RuntimeSourceFixture.methods("DisplayPresentations",
                        "reservedSource", "validateGraph", "validate", "canAttachOutput"),
                "DisplayPresentationGraph");
    }
}
