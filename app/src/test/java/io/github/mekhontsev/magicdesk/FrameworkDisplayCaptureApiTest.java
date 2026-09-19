package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class FrameworkDisplayCaptureApiTest {
    @Test public void captureFamilyChangesOnlyWhenNewClassExists() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Class<T> {
                    static boolean modern, denied; static int legacyLookups;
                    static final Class<?> oldApi = new Class<>(), newApi = new Class<>();
                    static Class<?> forName(String name) throws ClassNotFoundException {
                        if (name.equals("android.window.ScreenCaptureInternal")) {
                            if (denied) throw new SecurityException("denied");
                            if (modern) return newApi;
                            throw new ClassNotFoundException(name);
                        }
                        check(name.equals("android.window.ScreenCapture"), "capture family name");
                        legacyLookups++; return oldApi;
                    }
                }
                public static void verify() throws Exception {
                    check(captureClass() == Class.oldApi, "old capture family");
                    Class.modern = true;
                    check(captureClass() == Class.newApi, "new capture family");
                    Class.denied = true;
                    try { captureClass(); throw new AssertionError("denied ignored"); }
                    catch (SecurityException expected) { }
                    check(Class.legacyLookups == 1, "no fallback on permission failure");
                }
                """ + RuntimeSourceFixture.methods("FrameworkDisplayCaptureApi", "captureClass"));
    }

    @Test
    public void captionDownscaleDoesNotLoseLastPixel() {
        assertEquals(95, (int) (690 * (96f / 690)));
        assertEquals(96, (int) (690 * FrameworkDisplayCaptureApi.captureScale(690, 96)));
    }

    @Test
    public void displayAndDiagnosticDimensionsSurviveNativeTruncation() {
        for (int source = 1; source <= 8192; source++) {
            for (final int output : new int[]{1, 16, 96, 160, 1216, 1920, 2688, 8192}) {
                final float scale = FrameworkDisplayCaptureApi.captureScale(source, output);
                assertEquals(output, (int) (source * scale));
                assertEquals(output, (int) ((double) source * scale));
            }
        }
    }

    @Test
    public void invalidDimensionsAreRejectedBeforeCapture() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameworkDisplayCaptureApi.captureScale(0, 96));
        assertThrows(IllegalArgumentException.class,
                () -> FrameworkDisplayCaptureApi.captureScale(690, -1));
    }
}
