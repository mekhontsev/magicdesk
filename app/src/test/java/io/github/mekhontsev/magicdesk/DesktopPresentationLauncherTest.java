package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopPresentationLauncherTest {
    @Test public void newSourceUsesDesktopDefaultsRatherThanOutputDensity() throws Exception {
        verify("""
                output.densityDpi = 320;
                start(); complete(true);
                check(DisplayOperations.created == 1 && DesktopOperations.source.densityDpi == 108,
                        "creation ignored desktop density defaults");
                check(output.densityDpi == 320, "creation changed output density");
                """);
    }

    @Test public void failuresRetainSourceAndSuccessWaitsForDesktop() throws Exception {
        verify("""
                start();
                check(errors.isEmpty() && DisplayPresentations.opens == 0, "viewer preceded startup");
                complete(false);
                check(errors.get(0).contains("display:2") && ids.get(0) == 2, "failure lost retained identity");
                check(DisplayPresentations.opens == 0, "failed startup opened viewer");
                start(); complete(true);
                check(errors.get(1) == null && DisplayPresentations.opens == 1, "startup did not present output");
                check(DisplayOperations.created == 1, "retry created another compatible source");
                DisplayPresentations.outputs.clear();
                DesktopDisplayCatalog.displays.remove(2);
                DisplayOperations.failure = "profile not saved";
                start();
                check(errors.get(2).contains("profile not saved") && ids.get(2) == 2
                        && DisplayPresentations.opens == 1, "partial creation started a workspace");
                RuntimeCapabilities.supported = false;
                start();
                check(DisplayOperations.created == 2 && ids.get(3) == -1, "API 34 allocated a resource");
                """);
    }

    @Test public void countThenOriginThenIdSelectsWithoutChangingConfiguration() throws Exception {
        verify("""
                var a = source(5, 144); var b = source(6, 240); var c = source(7, 320);
                DesktopRuntimeBridge.active.addAll(Set.of(5, 6, 7));
                MagicDeskRuntime.managed.put(5, snapshot(task(5, true), task(5, true)));
                MagicDeskRuntime.managed.put(6, snapshot(task(6, true), task(6, false)));
                MagicDeskRuntime.managed.put(7, snapshot(task(7, true)));
                DisplayProfileStore.origins.put(c.uniqueId, "origin:3");
                start();
                check(DesktopOperations.source == c && TaskRepository.reads == 1, "count/origin/shared snapshot failed");
                complete(true);
                check(c.densityDpi == 320 && DisplayOperations.created == 0, "reused configuration changed");
                DisplayPresentations.outputs.clear(); DisplayProfileStore.origins.clear();
                start(); check(DesktopOperations.source == b, "tie was not stable by id"); complete(true);
                DisplayPresentations.outputs.clear();
                var empty = source(8, 400);
                start(); check(DesktopOperations.source == empty, "inactive source was not zero managed tasks"); complete(true);
                """);
    }

    @Test public void occupiedOrIncompatibleSourcesAreNotReused() throws Exception {
        verify("""
                var busy = source(5, 160);
                DisplayPresentations.outputs.put(99, new DisplayPresentations.Session(busy));
                source(6, 160).height++;
                source(7, 160).owned = false;
                source(8, 160).source = "overlay";
                source(9, 160).secure = true;
                source(10, 160).canHostDesktop = false;
                source(11, 160); DisplayPresentations.rejected.add(11);
                start(); complete(true);
                check(DisplayOperations.created == 1 && ids.get(0) == 2, "used an ineligible source");
                check(DisplayPresentations.outputs.get(99).source == busy, "stole another output");
                """);
    }

    @Test public void repeatsJoinAndKeepTheCurrentAttachment() throws Exception {
        verify("""
                var retained = source(5, 240); retained.width = 2560;
                DisplayPresentations.outputs.put(3, new DisplayPresentations.Session(retained));
                source(6, 160);
                start(); start();
                check(DesktopOperations.starts == 1 && DisplayOperations.created == 0, "repeat did not join launch");
                check(DesktopOperations.source == retained, "replaced current attachment with emptier source");
                DesktopPresentationLauncher.start(new Context(), new DesktopDisplayInfo(4), callback);
                check(ids.get(0) == -1 && errors.get(0) != null, "concurrent launch was accepted");
                complete(true);
                check(ids.equals(List.of(-1, 5, 5)) && errors.get(1) == null && errors.get(2) == null,
                        "joined callers did not receive completion");
                start(); complete(true);
                check(DisplayOperations.created == 0 && ids.get(3) == 5, "completed repeat created a source");
                """);
    }

    @Test public void unknownMembershipAndOutputChangesRemainExplicitFailures() throws Exception {
        verify("""
                source(5, 160); DesktopRuntimeBridge.active.add(5);
                MagicDeskRuntime.managed.put(5, new TaskRepository.Snapshot(List.of(), false, "ownership unknown"));
                start();
                check(errors.get(0).contains("ownership unknown") && DisplayOperations.created == 0,
                        "unknown membership became an empty source");
                MagicDeskRuntime.managed.put(5, snapshot());
                start();
                DisplayPresentations.outputs.put(3, new DisplayPresentations.Session(source(6, 160)));
                complete(true);
                check(errors.get(1).contains("attachment changed") && DisplayPresentations.opens == 0,
                        "startup replaced a newer attachment");
                DisplayPresentations.outputs.clear();
                start();
                var replacement = new DesktopDisplayInfo(3); replacement.uniqueId = "replacement";
                DesktopDisplayCatalog.displays.put(3, replacement);
                complete(true);
                check(errors.get(2) != null && DisplayPresentations.opens == 0, "stale output attached");
                """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { Context getApplicationContext() { return this; } }
                static class Handler { Handler(Object looper) {} void post(Runnable r) { r.run(); } }
                static class Looper { static Object getMainLooper() { return null; } }
                static class DisplayMetrics { static final int DENSITY_DEVICE_STABLE = 420; }
                static class TaskCommandQueue { static void execute(Runnable r) { r.run(); } }
                static class RuntimeCapabilities {
                    static boolean supported = true;
                    static void requireDesktop() { if (!supported) throw new IllegalStateException("API 35 required"); }
                }
                static class DesktopDisplayInfo {
                    int id, width = 1280, height = 720, densityDpi = 160;
                    String uniqueId, source = "virtual";
                    boolean owned = true, canHostDesktop = true, secure;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                }
                static class DesktopDisplayCatalog {
                    static Map<Integer, DesktopDisplayInfo> displays = new LinkedHashMap<>();
                    static DesktopDisplayInfo[] read() { return displays.values().toArray(DesktopDisplayInfo[]::new); }
                    static DesktopDisplayInfo require(int id, String uid) throws IOException {
                        var d = displays.get(id);
                        if (d == null || !d.uniqueId.equals(uid)) throw new IOException("stale display");
                        return d;
                    }
                }
                static class DesktopRuntimeBridge {
                    static Set<Integer> active = new HashSet<>();
                    static boolean hasWorkspace(int id) { return active.contains(id); }
                }
                static class Task {
                    int displayId; boolean managed;
                    Task(int id, boolean managed) { displayId = id; this.managed = managed; }
                }
                static class TaskRepository {
                    record Snapshot(List<Task> tasks, boolean available, String error) { }
                    static int reads;
                    static Snapshot loadAllNow() { reads++; return snapshot(); }
                }
                static class MagicDeskRuntime {
                    static Map<Integer, TaskRepository.Snapshot> managed = new HashMap<>();
                    static TaskRepository.Snapshot selectDesktopTaskSnapshot(int id, TaskRepository.Snapshot all) {
                        return managed.getOrDefault(id, snapshot());
                    }
                }
                static class DesktopManagedTaskPolicy {
                    static boolean isManagedApplicationTask(Task task) { return task.managed; }
                }
                static class VirtualDisplaySpec {
                    final int densityDpi;
                    VirtualDisplaySpec(int w, int h, int dpi) { densityDpi = dpi; }
                }
                static class DisplayProfileStore {
                    static Map<String, String> origins = new HashMap<>();
                    static String load(String key, int dpi) { return origins.getOrDefault(key, key); }
                }
                static class DisplayProfiles {
                    static String key(DesktopDisplayInfo d) { return d.uniqueId; }
                    static String origin(String value) { return value; }
                    static CreationDefaults desktopCreationDefaults(DesktopDisplayInfo ref, int maximumDpi) {
                        check(maximumDpi == 420, "desktop density cap was not forwarded");
                        return new CreationDefaults();
                    }
                    static class CreationDefaults {
                        int width = 1280, height = 720, densityDpi = 108;
                        String originProfileKey = "origin:3";
                        VirtualDisplaySpec spec(int w, int h, int dpi, boolean protectedContent) {
                            check(!protectedContent, "creation enabled protection implicitly");
                            return new VirtualDisplaySpec(w, h, dpi);
                        }
                    }
                }
                static class DisplayOperations {
                    static int created; static String failure;
                    static void createDisplay(VirtualDisplaySpec spec, boolean preview, DesktopPresentationLauncher.Callback c) {
                        check(!preview, "created overlay preview");
                        created++; c.onComplete(source(2, spec.densityDpi), failure);
                    }
                }
                static class DisplayPresentations {
                    static class Session { DesktopDisplayInfo source; Session(DesktopDisplayInfo d) { source = d; } }
                    static Map<Integer, Session> outputs = new HashMap<>();
                    static Set<Integer> rejected = new HashSet<>();
                    static Session forOutput(int id) { return outputs.get(id); }
                    static boolean canAttachOutput(DesktopDisplayInfo source, DesktopDisplayInfo output) {
                        return source.id != output.id && (!source.secure || output.secure) && !rejected.contains(source.id)
                                && outputs.values().stream().noneMatch(s -> s.source.id == source.id);
                    }
                    static int opens;
                    static void attachForDesktop(Context c, DesktopDisplayInfo source, DesktopDisplayInfo output,
                            Session previous, BuiltInWindowLauncher.Callback callback) {
                        try {
                            DesktopDisplayCatalog.require(output.id, output.uniqueId);
                            if (outputs.get(output.id) != previous) throw new IllegalStateException("attachment changed");
                            opens++; outputs.put(output.id, new Session(source));
                            callback.onComplete(null);
                        } catch (Exception error) { callback.onComplete(error); }
                    }
                }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class Result { boolean success; String message = "start rejected"; }
                static class DesktopOperations {
                    static java.util.function.Consumer<Result> pending;
                    static DesktopDisplayInfo source; static int starts;
                    static boolean isSessionTransitionInProgress() { return false; }
                    static boolean showDesktop(DesktopDisplayInfo d, java.util.function.Consumer<Result> callback) {
                        starts++; source = d; pending = callback; return true;
                    }
                }
                static class ShellAccess { static String usefulMessage(Throwable e) { return e.getMessage(); } }
                static DesktopDisplayInfo source(int id, int dpi) {
                    var d = new DesktopDisplayInfo(id); d.densityDpi = dpi; DesktopDisplayCatalog.displays.put(id, d); return d;
                }
                static Task task(int id, boolean managed) { return new Task(id, managed); }
                static TaskRepository.Snapshot snapshot(Task... tasks) {
                    return new TaskRepository.Snapshot(List.of(tasks), true, "");
                }
                static void start() { DesktopPresentationLauncher.start(new Context(), output, callback); }
                static void complete(boolean success) {
                    var r = new Result(); r.success = success; DesktopOperations.pending.accept(r);
                }
                static DesktopDisplayInfo output;
                static List<String> errors = new ArrayList<>();
                static List<Integer> ids = new ArrayList<>();
                static DesktopPresentationLauncher.Callback callback = (source, error) -> {
                    errors.add(error); ids.add(source == null ? -1 : source.id);
                };
                public static void verify() {
                    output = new DesktopDisplayInfo(3); output.source = "wired"; output.owned = false;
                    DesktopDisplayCatalog.displays.put(3, output);
                """ + body + "\n}\nstatic "
                + RuntimeSourceFixture.nestedClass("DesktopPresentationLauncher", "DesktopPresentationLauncher"));
    }
}
