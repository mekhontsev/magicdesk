package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.soc.qualcomm.QualcommDisplayModeBackend;

import java.io.IOException;

/** Composition point for optional SoC display-mode services. */
public final class SocDisplayModeBackends {
    private static final SocDisplayModeBackend[] BACKENDS = {
            new QualcommDisplayModeBackend()
    };

    private SocDisplayModeBackends() {
    }

    public static SocDisplayModeBackend.Snapshot queryExternal()
            throws IOException {
        IOException failure = null;
        for (final SocDisplayModeBackend backend : BACKENDS) {
            try {
                final SocDisplayModeBackend.Snapshot snapshot =
                        backend.queryExternal();
                if (snapshot != null) {
                    return snapshot;
                }
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return null;
    }

    public static void applyExternalTiming(
            final String backendId,
            final String timingKey) throws IOException {
        for (final SocDisplayModeBackend backend : BACKENDS) {
            if (backend.id().equals(backendId)) {
                backend.applyExternalTiming(timingKey);
                return;
            }
        }
        throw new IOException("SoC display backend is unavailable: "
                + backendId);
    }

    static void appendCapabilityProbe(final StringBuilder report) {
        for (final SocDisplayModeBackend backend : BACKENDS) {
            backend.appendCapabilityProbe(report);
        }
    }
}
