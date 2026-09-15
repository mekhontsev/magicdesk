package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Executes the production allocation branch without display or GPU resources. */
public final class ProtectedVirtualDisplayTest {
    @Test public void permissionAndProtectedSinkFormOneCreationContract() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final String SECURE_OUTPUT_PERMISSION = "secure-output";
                static class Context {
                    boolean allowed;
                    DisplayManager getSystemService(Class<?> type) { return manager; }
                }
                static final DisplayManager manager = new DisplayManager();
                static class DisplayManager {
                    static final int VIRTUAL_DISPLAY_FLAG_SECURE = 4;
                    boolean dropSecure;
                    int creations;
                    VirtualDisplay last;
                    VirtualDisplay createVirtualDisplay(String name, int w, int h, int dpi, Object surface, int flags) {
                        creations++;
                        return last = new VirtualDisplay(dropSecure ? flags & ~4 : flags);
                    }
                }
                static class Display {
                    static final int FLAG_SECURE = 4;
                    final int flags;
                    Display(int flags) { this.flags = flags; }
                    int getFlags() { return flags; }
                }
                static class VirtualDisplay {
                    final Display display;
                    boolean released;
                    VirtualDisplay(int flags) { display = new Display(flags); }
                    Display getDisplay() { return display; }
                    void release() { released = true; }
                }
                static class PixelFormat { static final int RGBA_8888 = 1; }
                static class ImageFormat { static final int PRIVATE = 34; }
                static class HardwareBuffer {
                    static final long USAGE_GPU_SAMPLED_IMAGE = 256, USAGE_PROTECTED_CONTENT = 16384;
                }
                static class ImageReader {
                    static int creations;
                    static ImageReader last;
                    int format; long usage; boolean closed;
                    static ImageReader newInstance(int w, int h, int format, int count) {
                        return newInstance(w, h, format, count, 3);
                    }
                    static ImageReader newInstance(int w, int h, int format, int count, long usage) {
                        creations++; last = new ImageReader(); last.format = format; last.usage = usage; return last;
                    }
                    Object getSurface() { return this; }
                    void close() { closed = true; }
                }
                static class OwnedDisplay {
                    final VirtualDisplay display; final ImageReader sink;
                    OwnedDisplay(Context c, VirtualDisplay d, ImageReader s) { display = d; sink = s; }
                }
                static boolean canCreateProtectedDisplay(Context c) { return c.allowed; }
                static int creationFlags() { return 1; }
                public static void verify() throws Exception {
                    Fixture api = new Fixture(); Context context = new Context();
                    VirtualDisplaySpec secure = new VirtualDisplaySpec(800, 600, 160, true);
                    try { api.create(context, secure); throw new AssertionError("permission ignored"); }
                    catch (SecurityException expected) { }
                    check(ImageReader.creations == 0 && manager.creations == 0, "resources allocated before permission check");
                    OwnedDisplay normal = api.create(context, new VirtualDisplaySpec(800, 600, 160));
                    check(normal.display.display.flags == 1 && normal.sink.format == PixelFormat.RGBA_8888,
                            "ordinary creation changed without permission");
                    context.allowed = true;
                    OwnedDisplay protectedDisplay = api.create(context, secure);
                    check(protectedDisplay.display.display.flags == 5, "secure display flag omitted");
                    check(protectedDisplay.sink.format == ImageFormat.PRIVATE && protectedDisplay.sink.usage == 16640,
                            "protected source has a CPU-readable sink");
                    manager.dropSecure = true;
                    try { api.create(context, secure); throw new AssertionError("silent protection downgrade"); }
                    catch (IllegalStateException expected) { }
                    check(manager.last.released && ImageReader.last.closed, "rejected display resources leaked");
                }
                """ + RuntimeSourceFixture.methods("FrameworkVirtualDisplayApi", "create"), "VirtualDisplaySpec");
    }
}
