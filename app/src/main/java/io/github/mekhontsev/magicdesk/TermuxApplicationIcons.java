package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
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
                                    final Bitmap bitmap = ApplicationIconBitmap.decode(records.get(i));
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

    private TermuxApplicationIcons() { }
}
