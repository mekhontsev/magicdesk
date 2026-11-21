package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;
import io.github.mekhontsev.magicdesk.hosted.HostedImageOutput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/** Session-owned, non-Activity hosts. Admission and input ownership run on the main thread. */
final class HostedOffscreen implements AutoCloseable {
    record Size(int width, int height) {
        Size { HostedImageOutput.validateSize(width, height); }
    }
    record Target(long window, Size size) { }
    static final class Output implements AutoCloseable {
        final long window;
        final HostedImageOutput image;
        final HostedSurfaceOutput input;
        private boolean closed;
        private float x, y;
        private boolean positioned;

        Output(long window, Size size, HostedSurfaceOutput input, Runnable changed) {
            this.window = window;
            this.input = input;
            image = new HostedImageOutput(size.width(), size.height(), new Handler(Looper.getMainLooper()), changed);
            try { input.setSurface(image.surface(), size.width(), size.height()); }
            catch (RuntimeException error) { close(); throw error; }
        }
        void requireReady() {
            if (closed || !image.ready()) throw new IllegalStateException("Offscreen output is not ready");
        }
        void pointer(float px, float py) {
            requireReady();
            validatePoint(px, py);
            positioned = true;
            x = px / image.width(); y = py / image.height();
            input.focus();
            input.pointer(x, y);
        }
        void requirePointer() {
            if (!positioned) throw new IllegalArgumentException("Supply x/y or move_pointer before clicking this output");
        }
        void validatePoint(float px, float py) {
            if (!Float.isFinite(px) || !Float.isFinite(py) || px < 0 || py < 0
                    || px >= image.width() || py >= image.height())
                throw new IllegalArgumentException("Pointer is outside the offscreen output");
        }
        void button(HostedSurfaceOutput.Button button, boolean down) {
            requireReady(); input.focus(); input.button(x, y, button, down);
        }
        void key(int key, boolean down) { requireReady(); input.focus(); input.key(key, 0, down); }
        void scroll(float horizontal, float vertical) {
            requireReady(); input.focus(); input.scroll(x, y, horizontal, vertical);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { input.close(); } finally { image.close(); }
        }
    }

    private final Map<Long, Output> outputs = new LinkedHashMap<>();
    private final LongFunction<HostedSurfaceOutput> open;
    private final Runnable changed;
    private Size size;
    private String error = "";

    HostedOffscreen(LongFunction<HostedSurfaceOutput> open, Runnable changed) { this.open = open; this.changed = changed; }
    boolean enabled() { return size != null; }
    String error() { return error; }
    void failed(RuntimeException failure) { error = ShellAccess.usefulMessage(failure); }
    Size size() { return size; }
    List<Output> outputs() { return List.copyOf(outputs.values()); }

    void configure(Size next) {
        if (java.util.Objects.equals(size, next) && error.isEmpty()) return;
        close();
        size = next;
    }

    void reconcile(boolean ready, List<Target> targets) {
        if (!enabled() || !ready || !error.isEmpty()) return;
        outputs.entrySet().removeIf(entry -> {
            if (targets.stream().anyMatch(target -> target.window() == entry.getKey()
                    && target.size().width() == entry.getValue().image.width()
                    && target.size().height() == entry.getValue().image.height())) return false;
            entry.getValue().close(); return true;
        });
        for (var target : targets) {
            long window = target.window();
            if (outputs.containsKey(window)) continue;
            try {
                if (outputs.size() >= 8) throw new IllegalStateException("At most eight offscreen outputs per session");
                long pixels = outputs.values().stream().mapToLong(output -> (long) output.image.width() * output.image.height()).sum();
                if (pixels + (long) target.size().width() * target.size().height() > 16_777_216)
                    throw new IllegalStateException("Offscreen session exceeds 16 megapixels");
                HostedSurfaceOutput input = open.apply(window);
                try { outputs.put(window, new Output(window, target.size(), input, changed)); }
                catch (RuntimeException failure) { input.close(); throw failure; }
                error = "";
            } catch (RuntimeException failure) { error = ShellAccess.usefulMessage(failure); break; }
        }
    }

    Output require(Long window) {
        if (window == null && outputs.size() == 1) return outputs.values().iterator().next();
        Output output = window == null ? null : outputs.get(window);
        if (output == null) throw new IllegalArgumentException("Select an offscreen windowId from graphics.list");
        return output;
    }

    @Override public void close() {
        size = null;
        for (var output : outputs.values()) output.close();
        outputs.clear(); error = "";
    }
}
