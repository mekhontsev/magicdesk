package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

/** One logical X screen follows its most recently focused Android host. */
final class X11Density {
    private final Map<Object, Double> hosts = new LinkedHashMap<>();
    private Object owner;
    private double scale;

    X11Density(double scale) {
        resolve(scale, 100);
        this.scale = scale;
    }

    void update(Object host, double scale, boolean focused) {
        resolve(scale, 100);
        hosts.put(host, scale);
        if (owner == null || focused) owner = host;
        if (owner == host) this.scale = scale;
    }

    void release(Object host) {
        hosts.remove(host);
        if (owner != host) return;
        owner = null;
        for (var entry : hosts.entrySet()) {
            owner = entry.getKey();
            scale = entry.getValue();
        }
    }

    int resolve(int scalePercent) { return resolve(scale, scalePercent); }

    static int resolve(double scale, int scalePercent) {
        if (!Double.isFinite(scale) || scale <= 0 || !AppPresentationProfile.isValidScale(scalePercent))
            throw new IllegalArgumentException("Invalid X11 scale");
        return (int) Math.max(24, Math.min(1536, Math.round(96 * scale * (scalePercent / 100.0))));
    }
}
