package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Platform projection UI, captions, and optional external output controls. */
public interface PlatformProjectionDriver {
    enum Transport {
        NONE,
        WIRED,
        WIRELESS
    }

    final class Mode {
        public final String timingKey;
        public final String displayLabel;

        public Mode(final String timingKey, final String displayLabel) {
            this.timingKey = timingKey == null ? "" : timingKey;
            this.displayLabel = displayLabel == null ? "" : displayLabel;
        }
    }

    final class ModeSelection {
        public final Mode current;
        public final Mode target;
        public final Mode defaultTarget;
        public final List<Mode> availableModes;
        public final boolean configurable;
        public final boolean systemDefaultAvailable;
        public final boolean systemDefaultSelected;

        public ModeSelection(
                final Mode current,
                final Mode target,
                final Mode defaultTarget,
                final List<Mode> availableModes,
                final boolean configurable) {
            this(
                    current,
                    target,
                    defaultTarget,
                    availableModes,
                    configurable,
                    false,
                    false);
        }

        public ModeSelection(
                final Mode current,
                final Mode target,
                final Mode defaultTarget,
                final List<Mode> availableModes,
                final boolean configurable,
                final boolean systemDefaultAvailable,
                final boolean systemDefaultSelected) {
            this.current = current;
            this.target = target;
            this.defaultTarget = defaultTarget;
            this.availableModes = availableModes == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(
                            new ArrayList<>(availableModes));
            this.configurable = configurable;
            this.systemDefaultAvailable = systemDefaultAvailable;
            this.systemDefaultSelected = systemDefaultAvailable
                    && systemDefaultSelected;
        }

        public ModeSelection withPreferredTiming(final String timingKey) {
            if (!configurable) {
                return this;
            }
            Mode preferred = defaultTarget;
            final boolean selectSystemDefault = systemDefaultAvailable
                    && (timingKey == null || timingKey.isEmpty());
            if (!selectSystemDefault && timingKey != null) {
                for (final Mode mode : availableModes) {
                    if (timingKey.equals(mode.timingKey)) {
                        preferred = mode;
                        break;
                    }
                }
            }
            return new ModeSelection(
                    current,
                    preferred,
                    defaultTarget,
                    availableModes,
                    true,
                    systemDefaultAvailable,
                    selectSystemDefault);
        }
    }

    interface PreparedMode extends AutoCloseable {
        int physicalDisplayId();

        boolean applyDeferredMode() throws IOException;

        @Override
        void close();
    }

    /** Whether MagicDesk can configure this platform's external output. */
    boolean supportsOutputConfiguration();

    /** Whether this platform exposes a verified wireless-display connection UI. */
    boolean hasWirelessConnectionUi(Context context);

    boolean openWirelessConnectionUi(Activity activity);

    ModeSelection readExternalDisplayModes(
            Context context,
            int displayId,
            String preferredTiming);

    /** Relinquish a previously selected output mode to the system. */
    void releaseExternalDisplayMode(int displayId) throws IOException;

    PreparedMode prepareExternalDisplay(
            Context context,
            int physicalDisplayId,
            DisplayProfileStore.Profile profile) throws IOException;

    boolean setCaptionTransport(Transport transport);
}
