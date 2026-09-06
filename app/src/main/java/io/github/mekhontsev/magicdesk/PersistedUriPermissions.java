package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shares process-wide Android grants among independently consumed results. */
final class PersistedUriPermissions {
    interface Access {
        void take(String uri, int flags);

        void release(String uri, int flags);
    }

    private final Access mAccess;
    private final Map<String, List<Grant>> mGrants = new HashMap<>();

    PersistedUriPermissions(final Access access) {
        mAccess = access;
    }

    synchronized Grant acquire(final String uri, final int flags) {
        if (uri == null || uri.isEmpty() || flags == 0) {
            throw new IllegalArgumentException("URI and permission flags are required");
        }
        mAccess.take(uri, flags);
        final Grant grant = new Grant(uri, flags);
        mGrants.computeIfAbsent(uri, ignored -> new ArrayList<>()).add(grant);
        return grant;
    }

    final class Grant {
        final String uri;
        private final int mFlags;
        private boolean mReleased;

        private Grant(final String uri, final int flags) {
            this.uri = uri;
            mFlags = flags;
        }

        boolean release() {
            synchronized (PersistedUriPermissions.this) {
                if (mReleased) {
                    return false;
                }
                mReleased = true;
                final List<Grant> remaining = mGrants.get(uri);
                remaining.remove(this);
                int retainedFlags = 0;
                for (final Grant grant : remaining) {
                    retainedFlags |= grant.mFlags;
                }
                if (remaining.isEmpty()) {
                    mGrants.remove(uri);
                }
                final int releasedFlags = mFlags & ~retainedFlags;
                if (releasedFlags == 0) {
                    return false;
                }
                mAccess.release(uri, releasedFlags);
                return true;
            }
        }
    }
}
