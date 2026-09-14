package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayPresentationMenuTest {
    @Test public void showOnUsesFullscreenWhileExplicitViewerKeepsControls() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { }
                static class Activity extends Context { }
                static class View { }
                interface Consumer<T> extends java.util.function.Consumer<T> { }
                static class R {
                    static class string {
                        static final int display_show_on = 1, display_park = 2, display_close_viewer = 3,
                                display_viewer = 4, display_viewer_source = 5, display_start_portable = 6;
                    }
                }
                static class android {
                    static class R { static class string { static final int cancel = 0; } }
                    static class os { static class Build { static class VERSION { static int SDK_INT = 35; } } }
                }
                static class DesktopDisplayInfo {
                    final int id;
                    final String name = "display";
                    DesktopDisplayInfo(int id) { this.id = id; }
                }
                enum DisplayPresentationMode {
                    DIRECT, MIRROR;
                    static DisplayPresentationMode forSource(DesktopDisplayInfo d) {
                        return d.id == 72 ? DIRECT : MIRROR;
                    }
                }
                static class RuntimeCapabilities { static boolean supportsDesktop(int sdk) { return sdk >= 35; } }
                static class PopupMenu {
                    static PopupMenu latest;
                    final Map<Integer, Item> items = new LinkedHashMap<>();
                    PopupMenu(Activity activity, View anchor) { latest = this; }
                    PopupMenu getMenu() { return this; }
                    Item add(int id) { Item item = new Item(); items.put(id, item); return item; }
                    void show() { }
                }
                static class Item {
                    boolean enabled;
                    java.util.function.Predicate<Item> click;
                    Item setEnabled(boolean value) { enabled = value; return this; }
                    void setOnMenuItemClickListener(java.util.function.Predicate<Item> action) { click = action; }
                }
                static class AlertDialog {
                    static java.util.function.BiConsumer<Object, Integer> selection;
                    static class Builder {
                        Builder(Activity activity) { }
                        Builder setTitle(int title) { return this; }
                        Builder setItems(String[] labels, java.util.function.BiConsumer<Object, Integer> action) {
                            selection = action; return this;
                        }
                        Builder setNegativeButton(int title, Object action) { return this; }
                        void show() { }
                    }
                }
                static class Toast {
                    static final int LENGTH_LONG = 1;
                    static String message;
                    static Toast makeText(Activity activity, String text, int duration) {
                        message = text; return new Toast();
                    }
                    void show() { }
                }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class DisplayPresentations {
                    static class Session { }
                    static Session forSource(int id) { return null; }
                    static void park(Session session) { throw new AssertionError("unexpected parking"); }
                    static int sourceId, outputId;
                    static boolean fullscreen;
                    static Throwable failure;
                    static void open(Context context, int source, int output, boolean full,
                            BuiltInWindowLauncher.Callback callback) {
                        sourceId = source; outputId = output; fullscreen = full;
                        callback.onComplete(failure);
                    }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "showOn") + """
                }
                static class DisplayPresentationMenu {
                """ + RuntimeSourceFixture.methods("DisplayPresentationMenu", "show", "label", "reportFailure") + """
                }
                static void choose(int action, DesktopDisplayInfo selected, DesktopDisplayInfo... catalog) {
                    DisplayPresentationMenu.show(new Activity(), new View(), selected, catalog,
                            d -> { throw new AssertionError("viewing started Desktop"); });
                    Item item = PopupMenu.latest.items.get(action);
                    check(item.enabled && item.click.test(item), "menu action unavailable");
                    AlertDialog.selection.accept(null, 0);
                }
                public static void verify() {
                    DesktopDisplayInfo source = new DesktopDisplayInfo(72);
                    DesktopDisplayInfo output = new DesktopDisplayInfo(70);
                    choose(R.string.display_show_on, source, source, output);
                    check(DisplayPresentations.fullscreen && DisplayPresentations.sourceId == 72
                            && DisplayPresentations.outputId == 70, "Show on opened Viewer controls");
                    // A parked source has no presentation; a reconnected output gets a new ID.
                    output = new DesktopDisplayInfo(73);
                    choose(R.string.display_show_on, source, source, output);
                    check(DisplayPresentations.fullscreen && DisplayPresentations.sourceId == 72
                            && DisplayPresentations.outputId == 73, "return used old output or Viewer controls");
                    choose(R.string.display_viewer, output, source, output);
                    check(!DisplayPresentations.fullscreen && DisplayPresentations.sourceId == 72
                            && DisplayPresentations.outputId == 73, "explicit Viewer lost its controls");
                    DisplayPresentations.failure = new IllegalStateException("output removed");
                    choose(R.string.display_show_on, source, source, output);
                    check("output removed".equals(Toast.message), "Show on failure was swallowed");
                }
                """);
    }
}
