package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplaySourceDialogTest {
    @Test public void sourceChoiceUsesSelectedOutputWithoutStartingDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { }
                static class Activity extends Context {
                    String getString(int id) {
                        if (id == R.string.display_desktop_active) return "Desktop active";
                        if (id == R.string.display_no_desktop) return "No Desktop";
                        if (id == R.string.display_viewer_no_sources) return "No other displays";
                        throw new AssertionError("unexpected string " + id);
                    }
                }
                static class R {
                    static class string {
                        static int display_show_another = 1, display_desktop_active = 2,
                                display_no_desktop = 3, display_viewer_no_sources = 4;
                    }
                }
                static class android { static class R { static class string { static int cancel = 0; } } }
                static class DesktopDisplayInfo {
                    final int id; final String name = "display", uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                }
                static class DesktopRuntimeBridge { static boolean hasWorkspace(int id) { return id == 72; } }
                static class AlertDialog {
                    static String[] items;
                    static java.util.function.BiConsumer<Object, Integer> selection;
                    static class Builder {
                        Builder(Activity activity) { }
                        Builder setTitle(int title) { return this; }
                        Builder setItems(String[] labels, java.util.function.BiConsumer<Object, Integer> action) {
                            items = labels; selection = action; return this;
                        }
                        Builder setNegativeButton(int title, Object action) { return this; }
                        void show() { }
                    }
                }
                static class Toast {
                    static final int LENGTH_LONG = 1;
                    static String message;
                    static Toast makeText(Activity activity, String text, int duration) { message = text; return new Toast(); }
                    void show() { }
                }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class DisplayPresentations {
                    static int sourceId, outputId;
                    static String sourceIdentity, outputIdentity;
                    static boolean fullscreen;
                    static Throwable failure;
                    static void attach(Context context, int source, int output, boolean full,
                            String sourceUniqueId, String outputUniqueId, Object expected,
                            BuiltInWindowLauncher.Callback callback) {
                        sourceIdentity = sourceUniqueId; outputIdentity = outputUniqueId;
                        sourceId = source; outputId = output; fullscreen = full; callback.onComplete(failure);
                    }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "attachOutput") + """
                }
                static class DisplaySourceDialog {
                """ + RuntimeSourceFixture.methods("DisplaySourceDialog", "show", "label", "reportFailure") + """
                }
                public static void verify() {
                    var source = new DesktopDisplayInfo(72); var output = new DesktopDisplayInfo(73);
                    var phone = new DesktopDisplayInfo(0);
                    DisplaySourceDialog.show(new Activity(), output, new DesktopDisplayInfo[]{output, source, phone});
                    check(Arrays.equals(AlertDialog.items, new String[]{"display [72]\\nDesktop active", "display [0]\\nNo Desktop"}),
                            "source chooser included its output or lost identity/Desktop status");
                    AlertDialog.selection.accept(null, 0);
                    check(DisplayPresentations.sourceId == 72 && DisplayPresentations.outputId == 73
                            && DisplayPresentations.fullscreen, "reversed source/output or opened windowed Viewer");
                    check(DisplayPresentations.sourceIdentity.equals(source.uniqueId)
                            && DisplayPresentations.outputIdentity.equals(output.uniqueId), "lost selected endpoint identities");
                    AlertDialog.selection.accept(null, 1);
                    check(DisplayPresentations.sourceId == 0 && DisplayPresentations.outputId == 73, "phone source rejected");
                    DisplayPresentations.failure = new IllegalStateException("output removed");
                    AlertDialog.selection.accept(null, 0);
                    check("output removed".equals(Toast.message), "failure was swallowed");
                    AlertDialog.items = null;
                    DisplaySourceDialog.show(new Activity(), output, new DesktopDisplayInfo[]{output});
                    check(AlertDialog.items == null && "No other displays".equals(Toast.message), "empty chooser was opened");
                }
                """);
    }
}
