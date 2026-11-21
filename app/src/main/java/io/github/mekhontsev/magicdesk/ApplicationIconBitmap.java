package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Bounded optional artwork decoder shared by remote application catalogs. */
final class ApplicationIconBitmap {
    private static final int ICON_SIZE = 96;

    static Bitmap decode(byte[] bytes) {
        return decode(bytes,ICON_SIZE);
    }

    static Bitmap decode(byte[] bytes,int target) {
        if (bytes.length == 0) return null;
        final var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (!"image/png".equals(options.outMimeType) || options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > 2048 || options.outHeight > 2048) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > target * 2)
            options.inSampleSize *= 2;
        final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null || Math.max(bitmap.getWidth(), bitmap.getHeight()) <= target) return bitmap;
        final float scale = (float) target / Math.max(bitmap.getWidth(), bitmap.getHeight());
        final Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }
    private ApplicationIconBitmap() { }
}
