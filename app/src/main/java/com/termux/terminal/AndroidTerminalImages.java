package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.IdentityHashMap;
import java.util.List;

/** Android codecs retain a single Bitmap per raster, shared by all attached terminal views. */
public final class AndroidTerminalImages {
    private final IdentityHashMap<TerminalImage, Bitmap> bitmaps = new IdentityHashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();

    public static final TerminalImage.Factory FACTORY = new TerminalImage.Factory() {
        @Override public TerminalImage fromArgb(int width, int height, int[] pixels) {
            TerminalImage.checkSize(width, height);
            return new BitmapImage(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888));
        }
        @Override public TerminalImage fromPng(byte[] data) { return decodePng(data); }
    };

    private static final class BitmapImage extends TerminalImage {
        final Bitmap bitmap;
        BitmapImage(Bitmap bitmap) { super(bitmap.getWidth(), bitmap.getHeight()); this.bitmap = bitmap; }
        @Override public int pixelAt(int x, int y) { return bitmap.getPixel(x, y); }
    }

    private static TerminalImage decodePng(byte[] data) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (data.length < signature.length) throw new IllegalArgumentException("EINVAL: PNG signature");
        for (int i = 0; i < signature.length; i++) if (data[i] != signature[i])
            throw new IllegalArgumentException("EINVAL: PNG signature");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        TerminalImage.checkSize(options.outWidth, options.outHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IllegalArgumentException("EINVAL: PNG data");
        return new BitmapImage(bitmap);
    }

    void retain(List<TerminalGraphics.Placement> placements) {
        bitmaps.keySet().removeIf(image -> placements.stream().noneMatch(p -> p.image == image));
        // Dropped bitmaps are left to Android, which may still reference them in a hardware display list.
    }

    void draw(Canvas canvas, List<TerminalGraphics.Placement> placements, int layer,
            int topRow, float cellWidth, float cellHeight) {
        for (TerminalGraphics.Placement p : placements) {
            int imageLayer = p.z < -1073741824 ? 0 : p.z < 0 ? 1 : 2;
            if (imageLayer != layer) continue;
            Bitmap bitmap = p.image instanceof BitmapImage androidImage ? androidImage.bitmap : bitmaps.get(p.image);
            if (bitmap == null) {
                int[] pixels = new int[p.image.width * p.image.height];
                for (int y = 0; y < p.image.height; y++) for (int x = 0; x < p.image.width; x++)
                    pixels[y * p.image.width + x] = p.image.pixelAt(x, y);
                bitmap = Bitmap.createBitmap(pixels, p.image.width, p.image.height, Bitmap.Config.ARGB_8888);
                bitmaps.put(p.image, bitmap);
            }
            source.set(p.sourceX, p.sourceY, p.sourceX + p.sourceWidth, p.sourceY + p.sourceHeight);
            destination.set(p.column * cellWidth, (p.row - topRow) * cellHeight,
                    (p.column + p.columns) * cellWidth, (p.row + p.rows - topRow) * cellHeight);
            int save = canvas.save();
            canvas.clipRect((p.column + p.clipLeft) * cellWidth, (p.row + p.clipTop - topRow) * cellHeight,
                    (p.column + p.clipRight) * cellWidth, (p.row + p.clipBottom - topRow) * cellHeight);
            canvas.drawBitmap(bitmap, source, destination, paint);
            canvas.restoreToCount(save);
        }
    }

    void drawPlaceholder(Canvas canvas, TerminalEmulator terminal, KittyImagePlaceholder cell,
            int column, int row, float cw, float ch) {
        TerminalGraphics.Placement p = terminal.getGraphics().virtualPlacement(terminal.getScreen(), cell.imageId, cell.placementId);
        if (p == null || cell.column >= p.columns || cell.row >= p.rows) return;
        // Application sessions store Android-native rasters; no per-cell bitmap copies or caches.
        if (!(p.image instanceof BitmapImage image)) return;
        float scale = Math.min(p.columns * cw / p.sourceWidth, p.rows * ch / p.sourceHeight);
        float width = p.sourceWidth * scale, height = p.sourceHeight * scale;
        float left = (column - cell.column) * cw + (p.columns * cw - width) / 2;
        float top = (row - cell.row) * ch + (p.rows * ch - height) / 2;
        source.set(p.sourceX, p.sourceY, p.sourceX + p.sourceWidth, p.sourceY + p.sourceHeight);
        destination.set(left, top, left + width, top + height);
        int save = canvas.save();
        canvas.clipRect(column * cw, row * ch, (column + 1) * cw, (row + 1) * ch);
        canvas.drawBitmap(image.bitmap, source, destination, paint);
        canvas.restoreToCount(save);
    }
}
