package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Map;

/** Adds diagnostics owned by one firmware platform. */
interface PlatformDiagnostics {
    void appendCapabilityProbe(StringBuilder report);

    void appendCompatibilityReport(StringBuilder report, Context context);

    void auditSelfTest(
            Context context,
            DesktopSelfTestResult result,
            Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry> capabilities);

    String supportDetail(
            PlatformDevice device,
            PlatformSupportLevel supportLevel);
}
