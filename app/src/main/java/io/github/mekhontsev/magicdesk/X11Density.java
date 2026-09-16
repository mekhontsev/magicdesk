package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

/** One logical X screen follows its most recently focused Android host. */
final class X11Density {
    private final Map<Object, Integer> hosts = new LinkedHashMap<>();
    private Object owner;
    private int density;

    X11Density(int density) { this.density = density; }

    void update(Object host, int density, boolean focused) {
        if (density <= 0) throw new IllegalArgumentException("Invalid Android density");
        hosts.put(host, density);
        if (owner == null || focused) owner = host;
        if (owner == host) this.density = density;
    }

    void release(Object host) {
        hosts.remove(host);
        if (owner != host) return;
        owner = null;
        for (var entry : hosts.entrySet()) {
            owner = entry.getKey();
            density = entry.getValue();
        }
    }

    int resolve(int scalePercent) { return resolve(density, scalePercent); }

    static int resolve(int density, int scalePercent) {
        if (density <= 0 || !AppPresentationProfile.isValidScale(scalePercent))
            throw new IllegalArgumentException("Invalid X11 scale");
        // Android's 160 dpi and X11's 96 dpi both mean 100%, not equal physical sizes.
        return (int) Math.max(24, Math.min(1536, Math.round(density * 96.0 * scalePercent / 16000)));
    }
}
