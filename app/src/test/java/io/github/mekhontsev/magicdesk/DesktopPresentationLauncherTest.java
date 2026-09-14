package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopPresentationLauncherTest {
    @Test public void desktopCompletionNotViewerAttachmentDeterminesLaunchResult() throws Exception {
        RuntimeSourceFixture.verify("""
                interface Callback { void onComplete(DesktopDisplayInfo source, String error); }
                static class Context { Context getApplicationContext() { return this; } }
                static class Handler { Handler(Object looper) {} void post(Runnable r) { r.run(); } }
                static class Looper { static Object getMainLooper() { return null; } }
                static class TaskCommandQueue { static void execute(Runnable r) { r.run(); } }
                static class RuntimeCapabilities {
                    static boolean supported = true;
                    static void requireDesktop() { if (!supported) throw new IllegalStateException("API 35 required"); }
                }
                static class DesktopDisplayInfo {
                    int id, width = 1280, height = 720, densityDpi = 160;
                    String uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                }
                static class DesktopDisplayCatalog { static void require(int id, String uniqueId) {} }
                static class VirtualDisplaySpec { VirtualDisplaySpec(int w, int h, int dpi) {} }
                static class DisplayOperations {
                    static int created;
                    static void createDisplay(VirtualDisplaySpec spec, boolean preview, Callback c) {
                        created++; c.onComplete(new DesktopDisplayInfo(2), null);
                    }
                }
                static class DisplayPresentations {
                    static int opens;
                    static void attach(Context c, int source, int output, boolean full,
                            BuiltInWindowLauncher.Callback callback) {
                        check(full && source == 2 && output == 3,
                                "portable Desktop did not use fullscreen output presentation");
                        opens++; callback.onComplete(null);
                    }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "attachOutput") + """
                }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class Result { boolean success; String message = "start rejected"; }
                static class DesktopOperations {
                    static java.util.function.Consumer<Result> pending;
                    static boolean showDesktop(DesktopDisplayInfo d, java.util.function.Consumer<Result> callback) {
                        pending = callback; return true;
                    }
                }
                static class ShellAccess { static String usefulMessage(Throwable e) { return e.getMessage(); } }
                public static void verify() {
                    List<String> errors = new ArrayList<>();
                    List<Integer> ids = new ArrayList<>();
                    Callback callback = (source, error) -> { errors.add(error); ids.add(source == null ? -1 : source.id); };
                    start(new Context(), new DesktopDisplayInfo(3), callback);
                    check(errors.isEmpty() && DisplayPresentations.opens == 0,
                            "viewer appeared before Desktop startup completed");
                    DesktopOperations.pending.accept(new Result());
                    check(errors.size() == 1 && errors.get(0).contains("display:2") && ids.get(0) == 2,
                            "startup rejection did not retain exact display identity");
                    check(DisplayPresentations.opens == 0, "failed Desktop startup opened a viewer");
                    start(new Context(), new DesktopDisplayInfo(3), callback);
                    Result ok = new Result(); ok.success = true;
                    DesktopOperations.pending.accept(ok);
                    check(errors.size() == 2 && errors.get(1) == null && DisplayPresentations.opens == 1,
                            "successful Desktop startup was not presented");
                    RuntimeCapabilities.supported = false;
                    start(new Context(), new DesktopDisplayInfo(3), callback);
                    check(DisplayOperations.created == 2 && ids.get(2) == -1, "API 34 created a desktop resource");
                }
                """ + RuntimeSourceFixture.methods("DesktopPresentationLauncher", "start", "retained"));
    }
}
