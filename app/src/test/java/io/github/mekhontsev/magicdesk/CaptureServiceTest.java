package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertThrows;

import java.io.IOException;

import org.junit.Test;

public final class CaptureServiceTest {
    @Test public void captureMetricsBelongToTargetNotLastApplicationActivity() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Point { int x, y; }
                static class Display {
                    int id;
                    boolean scoped;
                    Display(int id, boolean scoped) { this.id = id; this.scoped = scoped; }
                    void getRealSize(Point point) {
                        point.x = scoped && id == 0 ? 1216 : 480;
                        point.y = scoped && id == 0 ? 2688 : 800;
                    }
                    int getRotation() { return scoped && id == 0 ? 0 : 1; }
                }
                static class DisplayManager {
                    Display getDisplay(int id) { return id < 2 ? new Display(id, false) : null; }
                }
                static class Context {
                    int id;
                    DisplayManager getSystemService(Class<?> type) { return new DisplayManager(); }
                    Context createDisplayContext(Display display) {
                        Context context = new Context(); context.id = display.id; return context;
                    }
                    Display getDisplay() { return new Display(id, true); }
                }
                final Context mContext = new Context();
                record Frame(int displayId, int width, int height, int rotation) { }
                public static void verify() throws Exception {
                    Fixture fixture = new Fixture();
                    check(fixture.resolve(0).equals(new Frame(0, 1216, 2688, 0)),
                            "phone inherited virtual Activity metrics");
                    check(fixture.resolve(1).equals(new Frame(1, 480, 800, 1)), "virtual target metrics");
                    try { fixture.resolve(9); throw new AssertionError("absent display accepted"); }
                    catch (IOException expected) { }
                }
                """ + RuntimeSourceFixture.methods("CaptureService", "resolve"));
    }

    @Test public void nativeCropIsUnscaledAndPipeClosesBeforeGeometryCheck() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                private static final int MAX_CAPTURE_BYTES = 100;
                static int captures, resolutions;
                static boolean closed, geometryChanged, empty;
                static Rect lastCrop;
                static int lastWidth, lastHeight;
                record Rect(int left, int top, int right, int bottom) { }
                record Frame(int displayId, int width, int height, int rotation) {
                    DisplayCaptureSource source() { return DisplayCaptureSource.logical(displayId); }
                    void requireSameGeometry(Frame current) throws IOException {
                        check(closed, "pipe must close before geometry validation");
                        if (!equals(current)) throw new IOException("changed");
                    }
                }
                record Image(CaptureRequest request, int sourceWidth, int sourceHeight, int rotation,
                        CaptureRequest.Region region, byte[] png, Object task) { }
                Image captureTask(CaptureRequest request) { throw new AssertionError("display selected task"); }
                Frame resolve(int id) {
                    resolutions++;
                    return new Frame(id, 800, 600, geometryChanged && resolutions > 1 ? 2 : 0);
                }
                static class ShellAccess {
                    static byte[] openDisplayCapture(DisplayCaptureSource source, Rect crop, int w, int h) {
                        check(source.logicalDisplayId == 3, "logical display");
                        captures++;
                        lastCrop = crop; lastWidth = w; lastHeight = h;
                        return empty ? new byte[0] : new byte[]{1, 2, 3};
                    }
                }
                static class ParcelFileDescriptor {
                    static class AutoCloseInputStream extends ByteArrayInputStream {
                        AutoCloseInputStream(byte[] bytes) { super(bytes); }
                        @Override public void close() throws IOException { closed = true; super.close(); }
                    }
                }
                static void reset() {
                    captures = resolutions = 0;
                    closed = geometryChanged = empty = false;
                }
                public static void verify() throws Exception {
                    var fixture = new Fixture();
                    var region = new CaptureRequest.Region(51, 72, 201, 310);
                    var request = new CaptureRequest(CaptureRequest.Target.DISPLAY, 3, region);
                    var image = fixture.capture(request);
                    check(image.region().equals(region), "returned source coordinates");
                    check(lastCrop.equals(new Rect(51, 72, 201, 310)), "native crop, not full display");
                    check(lastWidth == 150 && lastHeight == 238, "no rescaling");
                    check(captures == 1 && resolutions == 2 && closed, "single capture and closed pipe");
                    reset();
                    fixture.capture(new CaptureRequest(CaptureRequest.Target.DISPLAY, 3, null));
                    check(lastCrop.equals(new Rect(0, 0, 800, 600)), "full capture");
                    reset();
                    try {
                        fixture.capture(new CaptureRequest(CaptureRequest.Target.DISPLAY, 3, new CaptureRequest.Region(0, 0, 801, 600)));
                        throw new AssertionError("outside bounds accepted");
                    } catch (IllegalArgumentException expected) {
                        check(captures == 0, "validate before shell work");
                    }
                    reset(); geometryChanged = true;
                    try { fixture.capture(request); throw new AssertionError("stale geometry accepted"); }
                    catch (IOException expected) { check(captures == 1 && closed, "no hidden retry"); }
                    reset(); empty = true;
                    try { fixture.capture(request); throw new AssertionError("empty capture accepted"); }
                    catch (IOException expected) { check(closed, "close pipe on read failure"); }
                }
                """ + RuntimeSourceFixture.methods("CaptureService", "capture", "readBounded"),
                "CaptureRequest", "DisplayCaptureSource");
    }

    @Test public void geometryIncludesRotationEvenWithoutDimensionChanges() throws Exception {
        final var frame = new CaptureService.Frame(3, 800, 600, 0);
        frame.requireSameGeometry(new CaptureService.Frame(3, 800, 600, 0));
        for (final var changed : new CaptureService.Frame[] {
                new CaptureService.Frame(3, 800, 600, 2),
                new CaptureService.Frame(3, 600, 800, 1),
                new CaptureService.Frame(3, 900, 600, 0)}) {
            assertThrows(IOException.class, () -> frame.requireSameGeometry(changed));
        }
        frame.requirePixel(0, 0);
        frame.requirePixel(799, 599);
        assertThrows(IllegalArgumentException.class, () -> frame.requirePixel(800, 0));
        assertThrows(IllegalArgumentException.class, () -> frame.requirePixel(0, 600));
        assertThrows(IllegalArgumentException.class, () -> frame.requirePixel(-1, 0));
    }

    @Test public void boundedPipeReaderRejectsEmptyOversizedAndFailedCaptures() throws Exception {
        RuntimeSourceFixture.verify("""
                private static final int MAX_CAPTURE_BYTES = 100;
                public static void verify() throws Exception {
                    byte[] bytes = new byte[100];
                    Arrays.fill(bytes, (byte) 7);
                    check(Arrays.equals(bytes, readBounded(new ByteArrayInputStream(bytes))), "exact limit");
                    for (byte[] invalid : new byte[][] {new byte[0], new byte[101]}) {
                        try { readBounded(new ByteArrayInputStream(invalid)); throw new AssertionError("accepted invalid capture"); }
                        catch (IOException expected) { }
                    }
                    try {
                        readBounded(new InputStream() {
                            public int read() throws IOException { throw new IOException("capture failed"); }
                        });
                        throw new AssertionError("lost pipe failure");
                    } catch (IOException expected) {
                        check(expected.getMessage().equals("capture failed"), "pipe error propagated");
                    }
                }
                """ + RuntimeSourceFixture.methods("CaptureService", "readBounded"));
    }
}
