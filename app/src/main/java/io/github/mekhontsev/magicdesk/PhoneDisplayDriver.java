package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.Display;

/** Hosts MagicDesk directly on the phone display. */
final class PhoneDisplayDriver implements DesktopDisplayDriver {
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
                    DesktopTaskAreaPolicy.SESSION,
                    false,
                    false);

    @Override
    public DesktopDisplayTarget.Kind kind() {
        return DesktopDisplayTarget.Kind.PHONE;
    }

    @Override
    public DesktopDisplayFeatures features() {
        return FEATURES;
    }

    @Override
    public DesktopDisplayTarget target(final int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("phone display id is required");
        }
        return DesktopDisplayTarget.phone();
    }

    @Override
    public void showReady(
            final Activity source,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (source == null) {
            throw new IllegalArgumentException("phone launch source is required");
        }
        if (target == null
                || target.kind != DesktopDisplayTarget.Kind.PHONE) {
            throw new IllegalArgumentException("phone target is required");
        }
        DesktopActivity.launch(source, target, policy);
    }

    @Override
    public boolean isSessionDisplayRemoval(
            final DesktopDisplayTarget target,
            final int removedDisplayId,
            final boolean activeDesktopRemoved) {
        return false;
    }

}
