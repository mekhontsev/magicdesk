package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads application UIDs owning live tasks on one display under shell identity. */
public final class ShellTaskUidReader {
    private ShellTaskUidReader() {
    }

    public static Set<Integer> read(final int displayId)
            throws ReflectiveOperationException {
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid desktop display " + displayId);
        }
        final LinkedHashSet<Integer> uids = new LinkedHashSet<>();
        final Object taskService = HiddenTaskApi.getService();
        for (final Object task : HiddenTaskApi.getTasks(taskService, displayId)) {
            final int uid = HiddenTaskApi.getTaskEffectiveUid(task);
            if (uid >= 10_000) {
                uids.add(Integer.valueOf(uid));
            }
        }
        return Collections.unmodifiableSet(uids);
    }
}
