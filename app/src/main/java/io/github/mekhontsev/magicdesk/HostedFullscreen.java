package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Ordinary Android immersive presentation; optional Desktop consumes the same request. */
final class HostedFullscreen implements AutoCloseable {
    private static final AtomicLong VERSIONS = new AtomicLong();
    private final Activity activity;
    private final View content;
    private final Consumer<Boolean> completed;
    private final HostedFullscreenState state = new HostedFullscreenState();
    private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> observe();
    private volatile BuiltInWindowRegistry.ImmersiveRequest snapshot;
    private long version;
    private boolean closed;

    HostedFullscreen(Activity activity, View content, Consumer<Boolean> completed) {
        this.activity = activity;
        this.content = content;
        this.completed = completed;
        content.addOnLayoutChangeListener(layout);
    }

    BuiltInWindowRegistry.ImmersiveRequest snapshot() { return snapshot; }

    void request(boolean fullscreen) {
        state.request(fullscreen, activity.isInMultiWindowMode());
        version = VERSIONS.incrementAndGet();
        applyBars();
        publish();
        observe();
    }

    void changed() {
        if (closed) return;
        if (activity.hasWindowFocus()) applyBars();
        publish();
        observe();
    }

    private void observe() {
        if (closed || snapshot == null) return;
        boolean requested = state.requested();
        Boolean result = state.observe(activity.isInMultiWindowMode());
        if (requested != state.requested()) {
            version = VERSIONS.incrementAndGet();
            applyBars();
            publish();
        }
        if (result != null) completed.accept(result);
    }

    void reject() {
        if (closed) return;
        state.reject();
        version = VERSIONS.incrementAndGet();
        applyBars();
        publish();
        completed.accept(false);
    }

    private void applyBars() {
        WindowInsetsController bars = activity.getWindow().getInsetsController();
        if (bars == null) return;
        if (state.requested()) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        } else bars.show(WindowInsets.Type.systemBars());
    }

    private void publish() {
        BuiltInWindowRegistry.ImmersiveRequest next = new BuiltInWindowRegistry.ImmersiveRequest(
                version, state.requested(), activity.hasWindowFocus());
        if (next.equals(snapshot)) return;
        snapshot = next;
        MagicDeskRuntime.refreshDesktopTasks();
    }

    @Override public void close() {
        closed = true;
        snapshot = null;
        content.removeOnLayoutChangeListener(layout);
        state.reject();
        applyBars();
        MagicDeskRuntime.refreshDesktopTasks();
    }
}
