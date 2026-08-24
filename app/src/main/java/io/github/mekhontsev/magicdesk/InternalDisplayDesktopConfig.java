package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.Resources;

/** Diagnostic view of Android's internal-display desktop resource. */
final class InternalDisplayDesktopConfig {
    private static final String RESOURCE_NAME =
            "config_canInternalDisplayHostDesktops";

    enum State {
        ENABLED,
        DISABLED,
        UNKNOWN
    }

    static final class Snapshot {
        final State state;
        final String detail;

        Snapshot(final State state, final String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    private InternalDisplayDesktopConfig() {
    }

    static Snapshot capture(final Context context) {
        if (context == null) {
            return unknown("framework resource was not inspected");
        }
        try {
            final Resources resources = context.getResources();
            final int resourceId = resources.getIdentifier(
                    RESOURCE_NAME, "bool", "android");
            if (resourceId == 0) {
                return unknown("framework resource is unavailable");
            }
            return fromValue(resources.getBoolean(resourceId));
        } catch (RuntimeException error) {
            return unknown("framework resource could not be read: "
                    + error.getClass().getSimpleName());
        }
    }

    static Snapshot fromValue(final boolean enabled) {
        return new Snapshot(
                enabled ? State.ENABLED : State.DISABLED,
                RESOURCE_NAME + '=' + enabled
                        + "; diagnostic only; actual support is verified by "
                        + "the desktop self-test");
    }

    private static Snapshot unknown(final String reason) {
        return new Snapshot(
                State.UNKNOWN,
                RESOURCE_NAME + "=unknown; " + reason
                        + "; diagnostic only");
    }
}
