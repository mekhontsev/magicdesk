package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Owns transient task state across controller callbacks and refreshes. */
final class DesktopTaskRuntimeRegistry {
    private final ConcurrentHashMap<Integer, DesktopTaskRuntimeState> mStates =
            new ConcurrentHashMap<>();

    DesktopTaskRuntimeState state(final int taskId) {
        return mStates.computeIfAbsent(
                Integer.valueOf(taskId),
                key -> new DesktopTaskRuntimeState(key.intValue()));
    }

    DesktopTaskRuntimeState find(final int taskId) {
        return mStates.get(Integer.valueOf(taskId));
    }

    boolean isCurrent(
            final int taskId,
            final DesktopTaskRuntimeState state) {
        return state != null
                && mStates.get(Integer.valueOf(taskId)) == state;
    }

    void forget(final int taskId) {
        mStates.remove(Integer.valueOf(taskId));
    }

    void clearNativeBoundsState() {
        for (final DesktopTaskRuntimeState state : mStates.values()) {
            state.clearNativeBoundsState();
        }
    }

    void clear() {
        mStates.clear();
    }

    List<DesktopTaskRuntimeState> snapshot() {
        return new ArrayList<>(mStates.values());
    }
}
