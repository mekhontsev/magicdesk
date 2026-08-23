package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Map;

/** Adds diagnostics owned by one firmware platform. */
public interface PlatformDiagnostics {
    void appendCapabilityProbe(StringBuilder report, Context context);

    void appendCompatibilityReport(StringBuilder report, Context context);

    void auditSelfTest(
            Context context,
            DesktopSelfTestResult result,
            Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry> capabilities);

}
