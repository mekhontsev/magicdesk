package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Optional catalog artwork. Transport and decoding never run inside icon binding. */
final class TermuxApplicationIcons {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int ICON_SIZE = 96;

    static void load(Context context, TermuxIntegration.Endpoint endpoint, Executor decoder,
            List<String> keys, Consumer<Map<String, Bitmap>> complete) {
        try {
            TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint,
                    TermuxIconCommand.create(keys), "MagicDesk application icons", endpoint.homeDirectory,
                    10_000, (result, failure) -> {
                        if (failure != null || result == null || !result.success()) {
                            complete.accept(Map.of());
                            return;
                        }
                        decoder.execute(() -> {
                            final Map<String, Bitmap> images = new HashMap<>();
                            try {
                                final List<byte[]> records = TermuxIconCommand.parse(result.stdout, keys.size());
                                for (int i = 0; i < keys.size(); i++) {
                                    final Bitmap bitmap = decode(records.get(i));
                                    if (bitmap != null) images.put(keys.get(i), bitmap);
                                }
                            } catch (RuntimeException ignored) {
                                // Artwork must never make a valid application catalog unavailable.
                            }
                            MAIN.post(() -> complete.accept(images));
                        });
                    });
        } catch (RuntimeException ignored) { complete.accept(Map.of()); }
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes.length == 0) return null;
        final var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (!"image/png".equals(options.outMimeType) || options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > 2048 || options.outHeight > 2048) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > ICON_SIZE * 2)
            options.inSampleSize *= 2;
        final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null || Math.max(bitmap.getWidth(), bitmap.getHeight()) <= ICON_SIZE) return bitmap;
        final float scale = (float) ICON_SIZE / Math.max(bitmap.getWidth(), bitmap.getHeight());
        final Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private TermuxApplicationIcons() { }
}
