package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Host-only contracts at the process and framework boundaries. */
public final class RuntimeBoundaryRegressionTest {
    @Test
    public void physicalCaptureIntegratesSharedRunnerWithRealPngBytes() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", captureFixture(true) + """
                public static void verify() throws Exception {
                    java.awt.image.BufferedImage png = new java.awt.image.BufferedImage(
                            2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    check(javax.imageio.ImageIO.write(png, "png", bytes), "host PNG encoder unavailable");
                    CaptureProcess.output = bytes.toByteArray();
                    BitmapFactory.decodePng = true;
                    capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 2, 2), 1, 1);
                    check(Arrays.equals(CaptureProcess.output, BitmapFactory.lastBytes), "PNG bytes changed");
                    check(BitmapFactory.last.recycled, "full frame leaked");
                    CaptureProcess.output = new byte[]{1, 2, 3};
                    try {
                        capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 2, 2), 1, 1);
                        throw new AssertionError("corrupt PNG accepted");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("no bitmap"), "missing decode diagnostic");
                    }
                }
                """, "BoundedProcessRunner");
    }

    @Test
    public void physicalCaptureUsesBoundedBinaryOutputBeforeDecoding() throws Exception {
        RuntimeSourceFixture.verify(captureFixture() + """
                public static void verify() throws Exception {
                    Bitmap result = capturePhysical(new DisplayCaptureSource(),
                            new Rect(0, 0, 2, 2), 1, 1);
                    check(BoundedProcessRunner.calls == 1, "capture bypassed bounded runner");
                    check(BitmapFactory.decoded == 1, "successful PNG was not decoded");
                    check(BitmapFactory.last.recycled, "full frame leaked");
                    check(result.width == 1 && result.height == 1, "sample size changed");
                    check(Canvas.draws == 1, "capture was not sampled");
                }
                """);
    }

    @Test
    public void physicalCaptureRejectsFailedOrTruncatedOutputBeforeDecoding() throws Exception {
        RuntimeSourceFixture.verify(captureFixture() + """
                public static void verify() throws Exception {
                    for (int scenario = 0; scenario < 2; scenario++) {
                        BoundedProcessRunner.exitCode = scenario == 0 ? 2 : 0;
                        BoundedProcessRunner.stdoutTruncated = scenario == 1;
                        try {
                            capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 2, 2), 1, 1);
                            throw new AssertionError("failed/truncated capture accepted");
                        } catch (IOException expected) {
                            check(BitmapFactory.decoded == 0, "failed output reached PNG decoder");
                        }
                    }
                }
                """);
    }

    @Test
    public void physicalCaptureRejectsCorruptPngAndPreservesCropValidation() throws Exception {
        RuntimeSourceFixture.verify(captureFixture() + """
                public static void verify() throws Exception {
                    BitmapFactory.corrupt = true;
                    try {
                        capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 2, 2), 1, 1);
                        throw new AssertionError("corrupt PNG accepted");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("capture"), "missing capture diagnostic");
                    }
                    BitmapFactory.corrupt = false;
                    try {
                        capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 3, 2), 1, 1);
                        throw new AssertionError("out-of-bounds crop accepted");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("exceeds"), "crop validation changed");
                        check(BitmapFactory.last.recycled, "invalid crop leaked full frame");
                    }
                }
                """);
    }

    @Test
    public void physicalCapturePreservesInterruption() throws Exception {
        RuntimeSourceFixture.verify(captureFixture() + """
                public static void verify() throws Exception {
                    BoundedProcessRunner.interrupt = true;
                    try {
                        capturePhysical(new DisplayCaptureSource(), new Rect(0, 0, 2, 2), 1, 1);
                        throw new AssertionError("interruption ignored");
                    } catch (IOException expected) {
                        check(Thread.currentThread().isInterrupted(), "interrupt flag lost");
                        check(BitmapFactory.decoded == 0, "interrupted output decoded");
                    } finally {
                        Thread.interrupted();
                    }
                }
                """);
    }

    @Test
    public void phoneRecoveryDelegatesRawFrameworkMemberToHiddenTaskApi() throws Exception {
        RuntimeSourceFixture.verify("""
                public static class Bundle {}
                public static class Service {
                    public int startActivityFromRecents(int taskId, Bundle options) { return 0; }
                }
                static class HiddenTaskApi {
                    static int calls, requestedTask, result;
                    static Object requestedService;
                    static int startActivityFromRecents(Object service, int taskId) {
                        calls++; requestedService = service; requestedTask = taskId;
                        return result;
                    }
                }
                public static void verify() throws Exception {
                    Object service = new Service();
                    startTaskFromRecents(service, 42);
                    check(HiddenTaskApi.calls == 1, "recovery reflected outside HiddenTaskApi");
                    check(HiddenTaskApi.requestedService == service
                            && HiddenTaskApi.requestedTask == 42, "recovery request changed");
                    HiddenTaskApi.result = -1;
                    try {
                        startTaskFromRecents(service, 42);
                        throw new AssertionError("rejected recent-task start accepted");
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().contains("42"), "task diagnostic lost");
                    }
                }
                """ + RuntimeSourceFixture.methods("PhoneDesktopTaskRecoveryCommand",
                "startTaskFromRecents"));
    }

    private static String captureFixture() throws Exception {
        return captureFixture(false);
    }

    private static String captureFixture(final boolean sharedRunner) throws Exception {
        return """
                static final int MAX_CAPTURE_BYTES = 32 * 1024 * 1024;
                static final int MAX_CAPTURE_ERROR_BYTES = 16 * 1024;
                static class DisplayCaptureSource { String physicalDisplayId = "123"; }
                static class Rect {
                    int left, top, right, bottom;
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                    String toShortString() { return left + "," + top + "," + right + "," + bottom; }
                }
                static class Bitmap {
                    enum Config { ARGB_8888 }
                    int width, height; boolean recycled;
                    Bitmap(int w, int h) { width=w; height=h; }
                    static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w,h); }
                    int getWidth() { return width; } int getHeight() { return height; }
                    void recycle() { recycled=true; }
                }
                static class BitmapFactory {
                    static int decoded; static boolean corrupt; static Bitmap last;
                    static boolean decodePng; static byte[] lastBytes;
                    static Bitmap decodeStream(InputStream input) { return decodeByteArray(new byte[0],0,0); }
                    static Bitmap decodeByteArray(byte[] bytes, int offset, int length) {
                        decoded++;
                        lastBytes=Arrays.copyOfRange(bytes, offset, offset+length);
                        if (decodePng) {
                            try {
                                java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(
                                        new ByteArrayInputStream(lastBytes));
                                last=image==null ? null : new Bitmap(image.getWidth(), image.getHeight());
                                return last;
                            } catch (IOException e) { return null; }
                        }
                        last = corrupt ? null : new Bitmap(2,2);
                        return last;
                    }
                }
                static class Paint { static final int FILTER_BITMAP_FLAG=1; Paint(int flags) {} }
                static class Canvas {
                    static int draws;
                    Canvas(Bitmap target) {}
                    void drawBitmap(Bitmap source, Rect from, Rect to, Paint paint) { draws++; }
                }
                static class ProcessBuilder {
                    ProcessBuilder(String... arguments) {
                        check(Arrays.equals(arguments, new String[]{"/system/bin/screencap", "-p", "-d", "123"}),
                                "physical display selector changed");
                    }
                    Process start() { return new CaptureProcess(); }
                }
                static class CaptureProcess extends Process {
                    static byte[] output=new byte[0];
                    public InputStream getInputStream() { return new ByteArrayInputStream(output); }
                    public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
                    public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    public int waitFor() { throw new AssertionError("unbounded physical capture waitFor"); }
                    public boolean waitFor(long timeout, TimeUnit unit) { return true; }
                    public int exitValue() { return 0; }
                    public void destroy() {}
                }
                """ + (sharedRunner ? "" : """
                static class BoundedProcessRunner {
                    static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
                    static int calls, exitCode;
                    static boolean stdoutTruncated, interrupt;
                    static class BinaryResult {
                        int exitCode; byte[] stdout=new byte[]{(byte)137,80,78,71};
                        String stderr="screencap diagnostic";
                        boolean stdoutTruncated, stderrTruncated;
                    }
                    static BinaryResult runBinary(Process process, long timeout, int outLimit, int errLimit)
                            throws InterruptedException {
                        calls++;
                        check(timeout > 0 && outLimit > 0 && errLimit > 0, "unbounded runner arguments");
                        if (interrupt) throw new InterruptedException("capture interrupted");
                        BinaryResult result=new BinaryResult();
                        result.exitCode=exitCode; result.stdoutTruncated=stdoutTruncated;
                        return result;
                    }
                }
                """) + RuntimeSourceFixture.methods("DisplayPixelProbe", "capturePhysical", "readText")
                .replace("StandardCharsets.UTF_8", "java.nio.charset.StandardCharsets.UTF_8");
    }
}
