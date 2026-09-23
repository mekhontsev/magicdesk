package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Event-thread ownership of surface sets in one explicitly selected layout scope. */
final class ShellLayoutScope {
    private final ShellLayout mLayout = new ShellLayout();
    private final List<Binding> mBindings = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();
    private long mNextIdentity;
    private ShellBounds mOutput;
    private ShellBounds mContent;

    Binding bind() {
        final Binding binding = new Binding(++mNextIdentity);
        mBindings.add(binding);
        return binding;
    }

    void resize(final ShellBounds output, final ShellBounds content) {
        if (output.equals(mOutput) && content.equals(mContent)) return;
        // Validate before replacing the scope's state.
        final var previous = mLayout.snapshot();
        mLayout.commit(output, content, surfaces());
        mOutput = output;
        mContent = content;
        publish(previous);
    }

    ShellLayout.Snapshot snapshot() { return mLayout.snapshot(); }
    void listen(final Runnable listener) {
        if (!mListeners.contains(listener)) mListeners.add(Objects.requireNonNull(listener));
    }
    void unlisten(final Runnable listener) { mListeners.remove(listener); }

    void clear() {
        final var previous = mLayout.snapshot();
        final boolean revoked = !mBindings.isEmpty();
        for (Binding binding : mBindings) {
            binding.mClosed = true;
            binding.mSurfaces = Map.of();
        }
        mBindings.clear();
        mLayout.clear();
        publish(previous, revoked);
    }

    private List<ShellSurface> surfaces() {
        return surfaces(null, Map.of());
    }

    private List<ShellSurface> surfaces(final Binding replacing,
            final Map<String, ShellSurface> replacement) {
        final List<ShellSurface> result = new ArrayList<>();
        for (Binding binding : mBindings) {
            result.addAll((binding == replacing ? replacement : binding.mSurfaces).values());
        }
        return result;
    }

    private void update() {
        if (mOutput == null) return;
        final var previous = mLayout.snapshot();
        mLayout.commit(mOutput, mContent, surfaces());
        publish(previous);
    }

    private void publish(final ShellLayout.Snapshot previous) {
        publish(previous, false);
    }

    private void publish(final ShellLayout.Snapshot previous, final boolean revoked) {
        final var current = mLayout.snapshot();
        if (!revoked && previous == current) return;
        for (Runnable listener : List.copyOf(mListeners)) {
            // A listener can release an owner. Never deliver the superseded state afterward.
            if (current != mLayout.snapshot()) break;
            if (mListeners.contains(listener)) listener.run();
        }
    }

    final class Binding implements AutoCloseable {
        private final long mIdentity;
        private Map<String, ShellSurface> mSurfaces = Map.of();
        private boolean mClosed;

        private Binding(final long identity) { mIdentity = identity; }

        boolean isClosed() { return mClosed; }

        void commit(final List<ShellSurface> surfaces) {
            replace(mOutput, mContent, surfaces);
        }

        void commit(final ShellBounds output, final ShellBounds content,
                final List<ShellSurface> surfaces) {
            replace(Objects.requireNonNull(output), Objects.requireNonNull(content), surfaces);
        }

        private void replace(final ShellBounds output, final ShellBounds content,
                final List<ShellSurface> surfaces) {
            if (mClosed) throw new IllegalStateException("Shell surface owner is closed");
            final Map<String, ShellSurface> next = new LinkedHashMap<>();
            for (ShellSurface surface : surfaces) {
                Objects.requireNonNull(surface);
                if (next.put(surface.id(), surface.withId(mIdentity + ":" + surface.id())) != null) {
                    throw new IllegalArgumentException("Duplicate shell surface identity");
                }
            }
            if (List.copyOf(next.values()).equals(List.copyOf(mSurfaces.values())) && Objects.equals(output, mOutput)
                    && Objects.equals(content, mContent)) return;
            final var previous = mLayout.snapshot();
            if (output != null) mLayout.commit(output, content, surfaces(this, next));
            mSurfaces = next;
            mOutput = output;
            mContent = content;
            publish(previous);
        }

        ShellLayout.Surface surface(final String id) {
            final ShellSurface request = mSurfaces.get(id);
            final ShellLayout.Snapshot snapshot = mLayout.snapshot();
            return mClosed || request == null || snapshot == null
                    ? null : snapshot.surfaces().get(request.id());
        }

        @Override public void close() {
            if (mClosed) return;
            mClosed = true;
            mSurfaces = Map.of();
            mBindings.remove(this);
            update();
        }
    }
}
