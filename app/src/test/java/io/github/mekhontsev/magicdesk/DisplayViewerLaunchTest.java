package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class DisplayViewerLaunchTest {
    @Test public void taskIdentityFollowsTheActivityForOrdinaryTaskClosure() throws Exception {
        for (String method : new String[] {"onCreate", "bindSource"}) {
            assertTrue(RuntimeSourceFixture.methods("DisplayViewerActivity", method)
                    .contains("mSession.taskId = getTaskId()"));
        }
        final String destroy = RuntimeSourceFixture.methods("DisplayViewerActivity", "onDestroy");
        assertTrue(destroy.contains("mSession.listener == this"));
        assertTrue(destroy.contains("mSession.taskId = -1"));
        assertTrue(destroy.contains("if (!isChangingConfigurations()) DisplayPresentations.detach(mSession)"));
        final String snapshot = RuntimeSourceFixture.methods("DisplayPresentations", "snapshot");
        assertTrue(snapshot.contains("put(\"taskId\", session.taskId)"));
        assertTrue(snapshot.contains("put(\"mode\", session.outputAttachment ? \"output\" : \"mirror\")"));
        assertTrue(snapshot.contains("put(\"immersive\", session.fullscreen)"));
        assertFalse(snapshot.contains("put(\"outputAttachment\""));
    }

    @Test public void attachmentUsesIndependentPlacementAndReusesItsViewer() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    void requirePresentationOutput(DesktopDisplayInfo output) { }
                }
                static class DesktopDisplayCatalog {
                    static DesktopDisplayInfo require(int id, String uniqueId) {
                        DesktopDisplayInfo display = new DesktopDisplayInfo(id);
                        if (uniqueId != null && !display.uniqueId.equals(uniqueId)) {
                            throw new IllegalStateException("stale output");
                        }
                        return display;
                    }
                }
                static class DesktopRuntimeBridge {
                    static Set<Integer> desktops = Set.of();
                    static Set<Integer> workspaceDisplayIds() { return desktops; }
                }
                static class Listener {
                    int shows;
                    void show(BuiltInWindowLauncher.Callback callback) { shows++; callback.onComplete(null); }
                }
                static class Session {
                    final String id = UUID.randomUUID().toString();
                    final DesktopDisplayInfo output;
                    DesktopDisplayInfo source;
                    boolean fullscreen, ready;
                    final boolean outputAttachment;
                    Object change;
                    Listener listener;
                    List<BuiltInWindowLauncher.Callback> completions = new ArrayList<>();
                    Session(DesktopDisplayInfo source, DesktopDisplayInfo output, boolean fullscreen, boolean attachment) {
                        outputAttachment = attachment;
                        this.source = source; this.output = output; this.fullscreen = fullscreen;
                    }
                }
                static class Main { void post(Runnable r) { r.run(); } }
                static final Main MAIN = new Main();
                static class TaskCommandQueue { static void execute(Runnable r) { r.run(); } }
                static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
                static Session forOutput(int outputId) {
                    return SESSIONS.values().stream().filter(s -> s.outputAttachment && s.output.id == outputId)
                            .findFirst().orElse(null);
                }
                static DesktopDisplayInfo requireSource(int id, String uniqueId) {
                    return DesktopDisplayCatalog.require(id, uniqueId);
                }
                static void validate(DesktopDisplayInfo source, DesktopDisplayInfo output, boolean attachment) {
                    check(source.id != output.id, "cannot view itself");
                }
                static void setFullscreen(Session s, boolean full) { s.fullscreen = full; }
                static void selectForAttachment(Session s, DesktopDisplayInfo source,
                        BuiltInWindowLauncher.Callback callback) {
                    s.source = source;
                    if (s.ready) callback.onComplete(null);
                    else s.completions.add(callback);
                }
                static void failed(Session s, Throwable error) {
                    for (var c : s.completions) c.onComplete(error);
                    s.completions.clear();
                }
                static void detach(Session s) { SESSIONS.remove(s.id); }
                static class DisplayViewerActivity {
                    static String createIntent(Context c) { return "selector"; }
                    static String createIntent(Context c, String id) { return id; }
                }
                static class ToolApplications {
                    static int opens;
                    static boolean failLaunch;
                    static ToolLaunchTarget lastTarget;
                    static void open(Context c, String sessionId, ToolLaunchTarget target, String uniqueId,
                            BuiltInWindowLauncher.Callback callback) {
                        lastTarget = target;
                        if (sessionId.equals("selector")) { opens++; callback.onComplete(null); return; }
                        var session = SESSIONS.get(sessionId);
                        check(!session.outputAttachment || !target.desktop,
                                "output Viewer inherited Desktop ownership from its output");
                        check(target.displayId == session.output.id && uniqueId.equals(session.output.uniqueId),
                                "Viewer launched on the wrong output identity");
                        target.requireCurrent(DesktopRuntimeBridge.desktops);
                        opens++;
                        callback.onComplete(failLaunch ? new IllegalStateException("launch failed") : null);
                    }
                }
                public static void verify() {
                    for (var desktops : List.of(Set.<Integer>of(), Set.of(0), Set.of(0, 3))) {
                        DesktopRuntimeBridge.desktops = desktops;
                        for (int output : new int[]{0, 3}) {
                            for (boolean fullscreen : new boolean[]{false, true}) {
                                SESSIONS.clear();
                                List<Throwable> results = new ArrayList<>();
                                int before = ToolApplications.opens;
                                openViewer(new Context(), ToolLaunchTarget.resolve("display", output, desktops),
                                        null, 8, true, fullscreen, results::add);
                                var session = forOutput(output);
                                check(session != null && session.fullscreen == fullscreen,
                                        "Viewer controls did not match the command");
                                check(ToolApplications.opens == before + 1 && results.isEmpty(),
                                        "dispatch was mistaken for attached Surface");
                                session.listener = new Listener();
                                session.ready = true;
                                session.completions.forEach(c -> c.onComplete(null));
                                session.completions.clear();
                                openViewer(new Context(), ToolLaunchTarget.resolve("display", output, desktops),
                                        null, 8, true, !fullscreen, results::add);
                                check(ToolApplications.opens == before + 1 && SESSIONS.size() == 1,
                                        "reattach created another Viewer task");
                                check(session.listener.shows == 1 && session.fullscreen != fullscreen
                                        && results.size() == 2 && results.stream().allMatch(Objects::isNull),
                                        "reattach failed to show the existing Viewer with requested controls");
                            }
                        }
                    }
                    SESSIONS.clear();
                    List<Throwable> rejected = new ArrayList<>();
                    int unopened = ToolApplications.opens;
                    attach(new Context(), 8, 3, true, "stale:8", "display:3", null, rejected::add);
                    attach(new Context(), 8, 3, true, "display:8", "stale:3", null, rejected::add);
                    check(rejected.size() == 2 && rejected.stream().allMatch(Objects::nonNull)
                            && ToolApplications.opens == unopened && SESSIONS.isEmpty(),
                            "stale endpoint selected from the dialog launched a Viewer");
                    rejected.clear();
                    Session previous = new Session(new DesktopDisplayInfo(8), new DesktopDisplayInfo(3), true, true);
                    SESSIONS.put(previous.id, previous);
                    int before = ToolApplications.opens;
                    attachForDesktop(new Context(), new DesktopDisplayInfo(8), new DesktopDisplayInfo(3), null, rejected::add);
                    previous.change = new Object();
                    attachForDesktop(new Context(), new DesktopDisplayInfo(8), new DesktopDisplayInfo(3), previous, rejected::add);
                    previous.change = null;
                    previous.source = new DesktopDisplayInfo(9);
                    attachForDesktop(new Context(), new DesktopDisplayInfo(8), new DesktopDisplayInfo(3), previous, rejected::add);
                    check(rejected.size() == 3 && rejected.stream().allMatch(Objects::nonNull)
                            && ToolApplications.opens == before && previous.source.id == 9,
                            "portable completion replaced a changed or pending attachment");

                    SESSIONS.clear();
                    DesktopRuntimeBridge.desktops = Set.of(0);
                    ToolLaunchTarget managed = ToolLaunchTarget.resolve("desktop", 0, Set.of(0));
                    ToolLaunchTarget independent = ToolLaunchTarget.resolve("display", 0, Set.of(0));
                    List<Throwable> results = new ArrayList<>();
                    openViewer(new Context(), managed, "display:0", null, false, false, results::add);
                    check(results.size() == 1 && results.get(0) == null && SESSIONS.isEmpty(),
                            "interactive selection created a binding");
                    check(ToolApplications.lastTarget == managed, "selector lost managed placement");
                    for (var target : List.of(managed, independent)) {
                        for (boolean immersive : new boolean[] {false, true}) {
                            results.clear();
                            int count = SESSIONS.size();
                            openViewer(new Context(), target, "display:0", 8, false, immersive, results::add);
                            check(results.isEmpty() && SESSIONS.size() == count + 1,
                                    "mirror launch failed or completed before Surface attachment");
                            Session mirror = new ArrayList<>(SESSIONS.values()).get(count);
                            check(!mirror.outputAttachment && mirror.fullscreen == immersive
                                    && ToolApplications.lastTarget == target,
                                    "mirror changed ownership or ignored immersive option");
                            mirror.completions.forEach(c -> c.onComplete(null));
                            mirror.completions.clear();
                            check(results.size() == 1 && results.get(0) == null, "Surface did not complete launch");
                        }
                    }
                    check(forOutput(0) == null, "mirrors reserved the output");
                    results.clear();
                    openViewer(new Context(), independent, "display:0", 9, true, true, results::add);
                    Session output = forOutput(0);
                    check(output != null && output.source.id == 9 && output.fullscreen
                            && results.isEmpty(), "output launch did not retain its binding contract");
                    output.ready = true;
                    output.completions.forEach(c -> c.onComplete(null));
                    output.completions.clear();
                    before = ToolApplications.opens;
                    openViewer(new Context(), independent, "display:0", 9, true, false, results::add);
                    check(forOutput(0) == output && !output.fullscreen && ToolApplications.opens == before,
                            "output launch duplicated a window instead of reusing it");
                    results.clear();
                    before = SESSIONS.size();
                    ToolApplications.failLaunch = true;
                    openViewer(new Context(), independent, "display:0", 10, false, false, results::add);
                    check(results.size() == 1 && results.get(0) != null && SESSIONS.size() == before,
                            "failed launch leaked a presentation");
                    ToolApplications.failLaunch = false;
                    for (boolean outputMode : new boolean[] {false, true}) {
                        results.clear();
                        openViewer(new Context(), independent, "stale:0", 10, outputMode, false, results::add);
                        check(results.size() == 1 && results.get(0) != null && SESSIONS.size() == before,
                                "stale output identity launched a Viewer");
                    }
                    reject(() -> openViewer(new Context(), managed, null, 8, true, true, results::add));
                    reject(() -> openViewer(new Context(), independent, null, null, true, false, results::add));
                    reject(() -> openViewer(new Context(), independent, null, null, false, true, results::add));
                    reject(() -> openViewer(new Context(), independent, null, -1, false, false, results::add));
                }
                static void reject(Runnable action) {
                    try { action.run(); throw new AssertionError("invalid Viewer request accepted"); }
                    catch (IllegalArgumentException expected) { }
                }
                """ + RuntimeSourceFixture.nestedClass("DisplayPresentations", "AttachmentExpectation")
                + "static " + RuntimeSourceFixture.nestedClass("ToolLaunchTarget", "ToolLaunchTarget")
                + RuntimeSourceFixture.methods("DisplayPresentations", "attach", "attachForDesktop", "launchViewer",
                        "openViewer", "mirror"));
    }
}
