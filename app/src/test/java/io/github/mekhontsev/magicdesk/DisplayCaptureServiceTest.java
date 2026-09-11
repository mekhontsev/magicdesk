package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertThrows;

import java.io.IOException;

import org.junit.Test;

public final class DisplayCaptureServiceTest {
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
                record Image(Frame display, DisplayCaptureRequest.Region region, byte[] png) { }
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
                    var region = new DisplayCaptureRequest.Region(51, 72, 201, 310);
                    var request = new DisplayCaptureRequest(3, region);
                    var image = fixture.capture(request);
                    check(image.region().equals(region), "returned source coordinates");
                    check(lastCrop.equals(new Rect(51, 72, 201, 310)), "native crop, not full display");
                    check(lastWidth == 150 && lastHeight == 238, "no rescaling");
                    check(captures == 1 && resolutions == 2 && closed, "single capture and closed pipe");
                    reset();
                    fixture.capture(new DisplayCaptureRequest(3, null));
                    check(lastCrop.equals(new Rect(0, 0, 800, 600)), "full capture");
                    reset();
                    try {
                        fixture.capture(new DisplayCaptureRequest(3, new DisplayCaptureRequest.Region(0, 0, 801, 600)));
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
                """ + RuntimeSourceFixture.methods("DisplayCaptureService", "capture", "readBounded"),
                "DisplayCaptureRequest", "DisplayCaptureSource");
    }

    @Test public void geometryIncludesRotationEvenWithoutDimensionChanges() throws Exception {
        final var frame = new DisplayCaptureService.Frame(3, 800, 600, 0);
        frame.requireSameGeometry(new DisplayCaptureService.Frame(3, 800, 600, 0));
        for (final var changed : new DisplayCaptureService.Frame[] {
                new DisplayCaptureService.Frame(3, 800, 600, 2),
                new DisplayCaptureService.Frame(3, 600, 800, 1),
                new DisplayCaptureService.Frame(3, 900, 600, 0)}) {
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
                """ + RuntimeSourceFixture.methods("DisplayCaptureService", "readBounded"));
    }
}
