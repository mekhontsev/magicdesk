package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** The sole optional window of a retained PTY. All mutations run on the UI thread. */
final class TerminalWindowAttachment {
    private WeakReference<Activity> owner = new WeakReference<>(null);
    private WeakReference<ConsoleTerminalView> surface = new WeakReference<>(null);
    private long generation;

    Activity owner() { return owner.get(); }
    ConsoleTerminalView view() { return surface.get(); }
    long generation() { return generation; }
    boolean present() { return owner.get() != null && surface.get() != null; }

    void replace(Activity activity, ConsoleTerminalView view) {
        Activity previous = owner.get();
        ConsoleTerminalView previousView = surface.get();
        if (previousView != null && previousView != view) previousView.attach(null, null);
        owner = new WeakReference<>(activity);
        surface = new WeakReference<>(view);
        generation++;
        if (previous != null && previous != activity && !previous.isDestroyed()) previous.finishAndRemoveTask();
    }

    boolean detach(Activity activity) {
        if (owner.get() != activity) return false;
        release();
        return true;
    }

    void release() {
        ConsoleTerminalView view = surface.get();
        // Revoke input/resize ownership before closing the Activity or the PTY.
        if (view != null) view.attach(null, null);
        owner.clear();
        surface.clear();
    }

    void close() {
        Activity activity = owner.get();
        release();
        if (activity != null && !activity.isDestroyed()) activity.finishAndRemoveTask();
    }

    void prune() {
        Activity activity = owner.get();
        if (activity != null && activity.isDestroyed()) release();
    }

    void notifyListener(Consumer<ConsoleTerminalSession.Listener> action) {
        Activity activity = owner.get();
        if (activity instanceof ConsoleTerminalSession.Listener listener
                && !activity.isDestroyed() && !activity.isFinishing()) action.accept(listener);
    }
}
