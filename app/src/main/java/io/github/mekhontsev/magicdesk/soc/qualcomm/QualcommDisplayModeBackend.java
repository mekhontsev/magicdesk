package io.github.mekhontsev.magicdesk.soc.qualcomm;

import io.github.mekhontsev.magicdesk.ShizukuCapabilityProbe;
import io.github.mekhontsev.magicdesk.SocDisplayModeBackend;

import android.os.RemoteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Qualcomm stable-AIDL implementation of the SoC display-mode contract. */
public final class QualcommDisplayModeBackend
        implements SocDisplayModeBackend {
    private static final String ID = "qualcomm-display-config";
    private static final String NAME = "Qualcomm IDisplayConfig";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Snapshot queryExternal() throws IOException {
        final QualcommDisplayConfigBridge.Snapshot snapshot =
                QualcommDisplayConfigBridge.queryExternal();
        if (!snapshot.available) {
            return null;
        }
        return convert(snapshot);
    }

    @Override
    public void applyExternalTiming(final String timingKey)
            throws IOException {
        QualcommDisplayConfigBridge.applyExternalTiming(timingKey);
    }

    @Override
    public void appendCapabilityProbe(final StringBuilder report) {
        try {
            final QualcommDisplayConfigBridge.Snapshot snapshot =
                    QualcommDisplayConfigBridge.queryDirect();
            if (!snapshot.available) {
                ShizukuCapabilityProbe.append(
                        report, "vendor.qti_display_config", "missing", "");
                return;
            }
            if (!snapshot.connected) {
                ShizukuCapabilityProbe.append(
                        report,
                        "vendor.qti_display_config",
                        "available",
                        "external display disconnected");
                return;
            }
            final List<String> modes = new ArrayList<>();
            for (final QualcommDisplayConfigBridge.Config config
                    : snapshot.configs) {
                modes.add(config.label());
            }
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.qti_display_config",
                    "available",
                    "external count=" + snapshot.configs.size()
                            + " active=" + snapshot.activeConfig
                            + " modes=" + modes);
        } catch (ReflectiveOperationException | RemoteException
                | RuntimeException error) {
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.qti_display_config",
                    "error",
                    usefulMessage(error));
        }
    }

    private Snapshot convert(
            final QualcommDisplayConfigBridge.Snapshot snapshot) {
        final List<Mode> modes = new ArrayList<>();
        for (final QualcommDisplayConfigBridge.Config config
                : snapshot.configs) {
            modes.add(new Mode(
                    config.index,
                    config.width,
                    config.height,
                    config.refreshRate));
        }
        return new Snapshot(
                id(),
                name(),
                snapshot.connected,
                snapshot.activeConfig,
                modes);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim().replace('\n', ' ');
    }
}
