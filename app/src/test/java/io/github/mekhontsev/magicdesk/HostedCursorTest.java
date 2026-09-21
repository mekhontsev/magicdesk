package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedCursorTest {
    @Test public void scalingCachesShapesAndPreservesHotspot() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("HostedCursor", "HostedCursor") + """
            static class Context { }
            record Bitmap(int width, int height) {
                static int scales;
                int getWidth() { return width; }
                int getHeight() { return height; }
                static Bitmap createScaledBitmap(Bitmap original, int width, int height, boolean filter) {
                    scales++;
                    return new Bitmap(width, height);
                }
            }
            record PointerIcon(Bitmap image, float x, float y) {
                static final int TYPE_NULL = 0;
                static final PointerIcon HIDDEN = new PointerIcon(null, 0, 0);
                static PointerIcon getSystemIcon(Context context, int type) { return HIDDEN; }
                static PointerIcon create(Bitmap image, float x, float y) {
                    check(x >= 0 && x < image.width && y >= 0 && y < image.height, "valid hotspot");
                    return new PointerIcon(image, x, y);
                }
            }
            public static void verify() {
                var context = new Context();
                var cursor = new HostedCursor();
                check(cursor.icon(context, 1) == null, "default before first shape");
                cursor.set(new Bitmap(24, 32), 23, 31, false);
                var normal = cursor.icon(context, 1);
                check(normal.image.width == 24 && normal.x == 23, "X pixels are not scaled by Android density again");
                for (int i = 0; i < 1000; i++) check(cursor.icon(context, 1) == normal, "reuse static shape");
                check(Bitmap.scales == 1, "no allocation on repeated requests");
                var half = cursor.icon(context, .5f);
                check(half.image.width == 12 && half.image.height == 16 && half.x == 11.5f && half.y == 15.5f,
                        "aspect fit scales hotspot and image together");
                var large = cursor.icon(context, 100);
                check(large.image.width == 384 && large.image.height == 512, "bounded bitmap size");
                var tiny = cursor.icon(context, .001f);
                check(tiny.image.width == 1 && tiny.image.height == 1 && tiny.x < 1 && tiny.y < 1, "subpixel content");
                check(cursor.icon(context, 0) == null && cursor.icon(context, Float.NaN) == null, "no content");
                cursor.set(null, 0, 0, true);
                check(cursor.icon(context, 1) == PointerIcon.HIDDEN, "explicit guest hiding");
                cursor.set(null, 0, 0, false);
                check(cursor.icon(context, 1) == null, "release restores Android default");
            }
            """);
    }
}
