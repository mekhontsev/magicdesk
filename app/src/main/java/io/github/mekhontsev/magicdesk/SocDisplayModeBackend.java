package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional SoC service that exposes physical external-display timings. */
public interface SocDisplayModeBackend {
    String id();

    String name();

    Snapshot queryExternal() throws IOException;

    void applyExternalTiming(String timingKey) throws IOException;

    void appendCapabilityProbe(StringBuilder report);

    final class Mode {
        public final int index;
        public final int width;
        public final int height;
        public final int refreshRate;

        public Mode(
                final int index,
                final int width,
                final int height,
                final int refreshRate) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        public String timingKey() {
            return width + "x" + height + "@" + refreshRate;
        }
    }

    final class Snapshot {
        public final String backendId;
        public final String backendName;
        public final boolean connected;
        public final int activeConfig;
        public final List<Mode> modes;

        public Snapshot(
                final String backendId,
                final String backendName,
                final boolean connected,
                final int activeConfig,
                final List<Mode> modes) {
            this.backendId = backendId == null ? "" : backendId;
            this.backendName = backendName == null ? "" : backendName;
            this.connected = connected;
            this.activeConfig = activeConfig;
            this.modes = modes == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(modes));
        }

        public Mode active() {
            for (final Mode mode : modes) {
                if (mode.index == activeConfig) {
                    return mode;
                }
            }
            return null;
        }
    }
}
