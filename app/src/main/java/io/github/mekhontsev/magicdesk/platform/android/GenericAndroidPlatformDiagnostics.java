package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.DesktopSelfTestCapabilityAudit;
import io.github.mekhontsev.magicdesk.DesktopSelfTestResult;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformSupportLevel;

import android.content.Context;

import java.util.Map;

/** Diagnostics for the standard Android platform profile. */
final class GenericAndroidPlatformDiagnostics implements PlatformDiagnostics {
    @Override
    public void appendCapabilityProbe(
            final StringBuilder report,
            final Context context) {
    }

    @Override
    public void appendCompatibilityReport(
            final StringBuilder report,
            final Context context) {
    }

    @Override
    public void auditSelfTest(
            final Context context,
            final DesktopSelfTestResult result,
            final Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry>
                    capabilities) {
    }

    @Override
    public String supportDetail(
            final PlatformDevice device,
            final PlatformSupportLevel supportLevel) {
        return "unverified Android 15+ profile; standard phone, simulated, "
                + "and direct secondary-display sessions are enabled";
    }
}
