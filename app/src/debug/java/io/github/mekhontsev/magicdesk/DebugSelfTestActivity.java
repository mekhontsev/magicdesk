package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Locale;

/** ADB-only entry point that starts the regular diagnostics self-test path. */
public final class DebugSelfTestActivity extends Activity {
    private static final String TAG = "MagicDeskDebugTest";
    private static final String EXTRA_TARGET = "target";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final LaunchTarget target = readTarget(getIntent());
        if (DeviceSetupManager.isRuntimeAuthorized()) {
            launchDiagnostics(target);
            return;
        }
        new Thread(() -> {
            final DeviceSetupManager.Audit audit;
            try {
                audit = DeviceSetupManager.audit(getApplicationContext());
            } catch (RuntimeException error) {
                Log.e(TAG, "device setup audit failed", error);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        finish();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!audit.canEnterMagicDesk()) {
                    startActivity(
                            DeviceSetupActivity.createLaunchIntent(this));
                    finish();
                    return;
                }
                // Process-local authorization is normally established by the
                // setup screen. Restore it after a cold ADB debug launch only
                // after the same setup audit has accepted the device state.
                DeviceSetupManager.authorizeRuntime(this);
                launchDiagnostics(target);
            });
        }, "MagicDeskDebugSelfTestSetup").start();
    }

    private void launchDiagnostics(final LaunchTarget target) {
        final Intent diagnostics = DiagnosticsActivity.createIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(
                        DiagnosticsActivity.EXTRA_SELF_TEST_TARGET,
                        target.selfTestTarget.name());
        if (target.displayKind != null) {
            diagnostics.putExtra(
                    DiagnosticsActivity.EXTRA_SELF_TEST_DISPLAY_KIND,
                    target.displayKind.name());
        }
        startActivity(diagnostics);
        finish();
    }

    private static LaunchTarget readTarget(final Intent intent) {
        final String value = intent == null
                ? null : intent.getStringExtra(EXTRA_TARGET);
        if (value == null || value.isEmpty()) {
            return LaunchTarget.SIMULATED;
        }
        try {
            return LaunchTarget.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LaunchTarget.SIMULATED;
        }
    }

    private enum LaunchTarget {
        PHONE(DesktopSelfTestTarget.PHONE, null),
        SIMULATED(DesktopSelfTestTarget.SIMULATED, null),
        WIRED(DesktopSelfTestTarget.EXTERNAL,
                DesktopDisplayTarget.Kind.WIRED),
        WIRELESS(DesktopSelfTestTarget.EXTERNAL,
                DesktopDisplayTarget.Kind.WIRELESS);

        final DesktopSelfTestTarget selfTestTarget;
        final DesktopDisplayTarget.Kind displayKind;

        LaunchTarget(
                final DesktopSelfTestTarget selfTestTarget,
                final DesktopDisplayTarget.Kind displayKind) {
            this.selfTestTarget = selfTestTarget;
            this.displayKind = displayKind;
        }
    }
}
