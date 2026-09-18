package io.github.mekhontsev.magicdesk;

import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Public display inventory. Addresses are connection-scoped, not physical profile identities. */
final class ApplicationDisplayCatalog implements DisplayManager.DisplayListener {
    private final DisplayManager manager;
    private final Map<Integer, String> identities = new HashMap<>();

    private ApplicationDisplayCatalog() {
        manager = MagicDeskApplication.applicationContext().getSystemService(DisplayManager.class);
        if (manager == null) throw new IllegalStateException("display service unavailable");
        manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
    }

    static DesktopDisplayInfo[] read() { return Holder.INSTANCE.snapshot(); }

    private synchronized DesktopDisplayInfo[] snapshot() {
        final Display[] displays = manager.getDisplays();
        final java.util.Set<Integer> live = new java.util.HashSet<>();
        final java.util.List<DesktopDisplayInfo> result = new java.util.ArrayList<>();
        for (final Display display : displays) {
            if (!display.isValid()) continue;
            final int id = display.getDisplayId();
            live.add(id);
            final String identity = identities.computeIfAbsent(id, unused -> "app-display:" + UUID.randomUUID());
            final DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            // Public APIs do not expose connection type, trust or stable physical identity.
            result.add(new DesktopDisplayInfo(id, identity, display.getName(), DisplayNames.name(display),
                    id == Display.DEFAULT_DISPLAY ? "phone" : "unknown",
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    false, false, false, (display.getFlags() & Display.FLAG_SECURE) != 0));
        }
        identities.keySet().retainAll(live);
        return result.toArray(new DesktopDisplayInfo[0]);
    }

    @Override public void onDisplayAdded(int id) { }
    @Override public void onDisplayChanged(int id) { }
    @Override public synchronized void onDisplayRemoved(int id) { identities.remove(id); }

    private static final class Holder {
        static final ApplicationDisplayCatalog INSTANCE = new ApplicationDisplayCatalog();
    }
}
