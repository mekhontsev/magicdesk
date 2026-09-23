package io.github.mekhontsev.magicdesk;

import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Borrowed shell views in HOME. Android retains ownership of its task, focus and stacking. */
@android.annotation.SuppressLint("RtlHardcoded") // Shell bounds are physical display coordinates, independent of locale.
final class DesktopHomeSurfaceHost implements AutoCloseable {
    private final FrameLayout background, bottom;
    private final Set<Window> windows = new LinkedHashSet<>();
    private boolean closed;

    DesktopHomeSurfaceHost(FrameLayout background, FrameLayout bottom) {
        this.background = background;
        this.bottom = bottom;
    }

    HostedShellWindows.Host host(Function<HostedShellWindows.Surface, HostedShellOutput> outputs) {
        return new HostedShellWindows.Host() {
            @Override public void validate(HostedShellWindows.Surface state) {
                checkThread();
                if (closed) throw new IllegalStateException("Desktop HOME host is unavailable");
                if ((state.layer() != ShellSurface.Layer.BACKGROUND && state.layer() != ShellSurface.Layer.BOTTOM)
                        || state.keyboard() != ShellSurface.Keyboard.NONE)
                    throw new UnsupportedOperationException("HOME requires BACKGROUND/BOTTOM with keyboard NONE");
            }

            @Override public HostedShellWindows.Window open(HostedShellWindows.Surface state) {
                validate(state);
                var parent = state.layer() == ShellSurface.Layer.BACKGROUND ? background : bottom;
                if (!parent.isAttachedToWindow()) throw new IllegalStateException("Desktop HOME is not attached");
                var output = outputs.apply(state);
                Window window = null;
                try {
                    window = new Window(parent, output);
                    windows.add(window);
                    window.place(state.bounds());
                    parent.addView(window.view);
                    return window;
                } catch (RuntimeException error) {
                    if (window != null) window.close(); else output.close();
                    throw error;
                }
            }
        };
    }

    private final class Window implements HostedShellWindows.Window {
        private final FrameLayout parent;
        private final HostedShellTextureView view;
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private boolean released;

        Window(FrameLayout parent, HostedShellOutput output) {
            this.parent = parent;
            view = new HostedShellTextureView(parent.getContext(), output, this::finish);
        }

        @Override public CompletableFuture<Void> ready() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> ended() { return ended.copy(); }

        @Override public CompletableFuture<Void> present(ShellBounds bounds, HostedShellFrame frame) {
            checkThread();
            if (released) throw new IllegalStateException("HOME shell view is released");
            var receipt = view.present(frame);
            place(bounds);
            return receipt;
        }

        private void place(ShellBounds bounds) {
            if (bounds.isEmpty()) throw new IllegalArgumentException("Empty shell view");
            int[] origin = new int[2];
            parent.getLocationOnScreen(origin);
            var params = new FrameLayout.LayoutParams(bounds.width(), bounds.height(), Gravity.TOP | Gravity.LEFT);
            params.leftMargin = bounds.left() - origin[0];
            params.topMargin = bounds.top() - origin[1];
            view.setLayoutParams(params);
        }

        @Override public void close() {
            finish(null);
        }

        private void finish(Throwable error) {
            checkThread();
            if (released) return;
            released = true;
            windows.remove(this);
            view.close();
            parent.removeView(view);
            if (error == null) ended.complete(null); else ended.completeExceptionally(error);
        }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        for (var window : List.copyOf(windows)) window.close();
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("HOME shell host requires main thread");
    }
}
