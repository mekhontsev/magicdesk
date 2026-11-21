package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit launch provenance shared by all workspace activity-start guards. */
final class ShellActivityLaunchScope implements AutoCloseable {
    private static final Set<ShellActivityLaunchScope> ACTIVE =
            ConcurrentHashMap.newKeySet();

    private final LaunchActivityIdentity mIdentity;
    private final int mDisplayId;
    private volatile RuntimeException mRejection;

    private ShellActivityLaunchScope(final LaunchActivityIdentity identity, final int displayId) {
        mIdentity = java.util.Objects.requireNonNull(identity);
        if (displayId < 0) { throw new IllegalArgumentException("launch display is required"); }
        mDisplayId = displayId;
    }

    static ShellActivityLaunchScope begin(final LaunchActivityIdentity identity, final int displayId) {
        final ShellActivityLaunchScope scope = new ShellActivityLaunchScope(identity, displayId);
        ACTIVE.add(scope);
        return scope;
    }

    static boolean isActive(final Intent intent, final String packageName) {
        // IActivityController callbacks arrive on Binder threads without the
        // caller or display options. Intent flags are not launch provenance.
        for (final ShellActivityLaunchScope scope : ACTIVE) {
            if (scope.mIdentity.matchesStart(intent, packageName)) {
                return true;
            }
        }
        return false;
    }

    static boolean rejectOnDisplay(final Intent intent, final String packageName, final int displayId,
            final RuntimeException rejection) {
        final java.util.List<ShellActivityLaunchScope> matches = new java.util.ArrayList<>();
        for (final ShellActivityLaunchScope scope : ACTIVE) {
            if (scope.mIdentity.matchesStart(intent, packageName)) {
                // Simultaneous launches of the same Activity on different displays
                // are ambiguous to IActivityController. Never turn that into a veto.
                if (scope.mDisplayId != displayId) { return false; }
                matches.add(scope);
            }
        }
        // A common destination suffices to veto, but not to attribute the error to both callers.
        if (matches.size() == 1) matches.get(0).mRejection = rejection;
        return !matches.isEmpty();
    }

    void requireAdmitted() {
        final RuntimeException failure = mRejection;
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
    }
}
