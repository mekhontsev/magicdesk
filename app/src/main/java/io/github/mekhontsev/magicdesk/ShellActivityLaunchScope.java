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

    static boolean isActiveOnDisplay(final Intent intent, final String packageName, final int displayId) {
        boolean matched = false;
        for (final ShellActivityLaunchScope scope : ACTIVE) {
            if (scope.mIdentity.matchesStart(intent, packageName)) {
                // Simultaneous launches of the same Activity on different displays
                // are ambiguous to IActivityController. Never turn that into a veto.
                if (scope.mDisplayId != displayId) { return false; }
                matched = true;
            }
        }
        return matched;
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
    }
}
