package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Map;

/** Diagnostics for the conservative Generic Android platform profile. */
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
        return "unverified Generic Android profile; local and simulated "
                + "desktops only";
    }
}
