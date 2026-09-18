package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkTaskCaptureApiTest {
    @Test public void captureRequestsFreshPixelsWithoutUpdatingRecentCache() throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object marker = new Object();
                public static class Service {
                    public Object takeTaskSnapshot(int id, boolean updateCache) {
                        check(id == 42 && !updateCache, "fresh-only task capture");
                        return marker;
                    }
                }
                static Object getService() { return new Service(); }
                public static void verify() throws Exception {
                    check(takeTaskSnapshot(42) == marker, "task result retained");
                }
                """ + RuntimeSourceFixture.methods("HiddenTaskApi", "takeTaskSnapshot"));
    }

    @Test public void frameOwnsGeometryAndReleasesNativeResourcesOnEveryPath() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static boolean hardwareRecycled, softwareRecycled, bufferClosed;
                static boolean failCopy, missingBuffer, real = true;
                static int frameWidth = 800;
                static class Point { int x = 1600, y = 1200; }
                static class ColorSpace { }
                static class ComponentName { String flattenToShortString() { return "example/.Main"; } }
                static class HardwareBuffer implements AutoCloseable {
                    int getWidth() { return frameWidth; }
                    int getHeight() { return 600; }
                    public void close() { bufferClosed = true; }
                }
                static class Bitmap {
                    enum Config { ARGB_8888 }
                    int kind;
                    Bitmap(int kind) { this.kind = kind; }
                    static Bitmap wrapHardwareBuffer(HardwareBuffer b, ColorSpace c) { return new Bitmap(1); }
                    Bitmap copy(Config c, boolean mutable) { return failCopy ? null : new Bitmap(2); }
                    static Bitmap createBitmap(Bitmap b, int x, int y, int width, int height) {
                        return x == 0 && y == 0 && width == 800 && height == 600 ? b : new Bitmap(3);
                    }
                    void recycle() {
                        if (kind == 1) hardwareRecycled = true;
                        if (kind == 2) softwareRecycled = true;
                    }
                }
                public static class Snapshot {
                    public HardwareBuffer getHardwareBuffer() { return missingBuffer ? null : new HardwareBuffer(); }
                    public boolean isRealSnapshot() { return real; }
                    public Point getTaskSize() { return new Point(); }
                    public ColorSpace getColorSpace() { return new ColorSpace(); }
                    public int getRotation() { return 1; }
                    public ComponentName getTopActivityComponent() { return new ComponentName(); }
                }
                static class TaskCapture {
                    record Info(int taskId, int width, int height, int taskWidth, int taskHeight,
                            int rotation, String topActivity) { }
                }
                record Frame(TaskCapture.Info info, Bitmap bitmap) { }
                static void reset() {
                    hardwareRecycled = softwareRecycled = bufferClosed = failCopy = missingBuffer = false;
                    real = true; frameWidth = 800;
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    Frame full = f.read(new Snapshot(), 42, null);
                    check(full.bitmap.kind == 2 && !softwareRecycled, "result ownership transferred");
                    check(bufferClosed && hardwareRecycled, "hardware capture released");
                    check(full.info.width() == 800 && full.info.taskWidth() == 1600, "platform scale explicit");
                    check(full.info.taskId() == 42, "frame identity");
                    reset();
                    Frame crop = f.read(new Snapshot(), 42, new CaptureRequest.Region(1, 2, 101, 52));
                    check(crop.bitmap.kind == 3 && softwareRecycled && hardwareRecycled && bufferClosed, "crop cleanup");
                    reset(); real = false;
                    try { f.read(new Snapshot(), 42, null); throw new AssertionError("theme image accepted"); }
                    catch (IOException expected) { check(bufferClosed, "rejected snapshot buffer released"); }
                    reset(); frameWidth = 8193;
                    try { f.read(new Snapshot(), 42, null); throw new AssertionError("oversize accepted"); }
                    catch (IOException expected) { check(bufferClosed, "oversize released"); }
                    reset(); failCopy = true;
                    try { f.read(new Snapshot(), 42, null); throw new AssertionError("failed copy accepted"); }
                    catch (IOException expected) { check(bufferClosed && hardwareRecycled, "copy failure cleanup"); }
                    reset();
                    try { f.read(new Snapshot(), 42, new CaptureRequest.Region(0, 0, 801, 600));
                        throw new AssertionError("outside region accepted"); }
                    catch (IllegalArgumentException expected) { check(bufferClosed, "region failure cleanup"); }
                    reset(); missingBuffer = true;
                    try { f.read(new Snapshot(), 42, null); throw new AssertionError("missing buffer accepted"); }
                    catch (IOException expected) { }
                }
                """ + RuntimeSourceFixture.methods("FrameworkTaskCaptureApi", "read"), "CaptureRequest");
    }

    @Test public void missingTaskFailsWithoutCapturingAnotherSource() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static int calls;
                static class Rect { int left, top, right, bottom; }
                static class EventDrivenWaits {
                    enum Reason { TASK_CAPTURE }
                    static void noteFrameworkWait(Reason reason) { }
                }
                static class HiddenTaskApi {
                    static Object takeTaskSnapshot(int id) { calls++; return null; }
                }
                record Frame() { }
                Frame read(Object snapshot, int task, CaptureRequest.Region region) {
                    throw new AssertionError("missing snapshot must not be decoded");
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    try { f.capture(42, null); throw new AssertionError("hidden task accepted"); }
                    catch (IOException expected) { check(calls == 1, "no fallback or retry"); }
                    try { f.capture(-1, null); throw new AssertionError("negative id accepted"); }
                    catch (IllegalArgumentException expected) { check(calls == 1, "validated before Binder"); }
                }
                """ + RuntimeSourceFixture.methods("FrameworkTaskCaptureApi", "capture"), "CaptureRequest");
    }
}
