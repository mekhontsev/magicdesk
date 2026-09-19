package io.github.mekhontsev.magicdesk.platform.nubia;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.Set;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.MagicDeskApplication;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;
import io.github.mekhontsev.magicdesk.R;
import io.github.mekhontsev.magicdesk.ShellAccess;

/** Serializes temporary projection overrides independently of app window policy. */
final class NubiaProjectionDesktopState implements PlatformProjectionDriver.DesktopOption {
    private static final String PACKAGE = "cn.nubia.touping";
    private static final String PREFS = "nubia_desktop_projection";
    private static final String ENABLED = "privacy_shield_guard";
    private static final String RESTORE = "package_restore_state";
    private Set<NubiaCaptionVisibilityManager.Transport> mTransports = Set.of();
    private Boolean mSessionEnabled;
    private boolean mLastSuccess = true;
    private String mError = "";

    @Override public int titleResource() { return R.string.settings_nubia_projection_guard; }
    @Override public int summaryResource() { return R.string.settings_nubia_projection_guard_summary; }
    @Override public boolean isEnabled() { return preferences().getBoolean(ENABLED, true); }
    @Override public boolean setEnabled(final boolean enabled) {
        return preferences().edit().putBoolean(ENABLED, enabled).commit();
    }
    @Override public boolean reset() { return preferences().edit().remove(ENABLED).commit(); }

    synchronized boolean setTransports(final Set<NubiaCaptionVisibilityManager.Transport> targets) {
        if (targets.equals(mTransports) && mLastSuccess && mSessionEnabled != null) {
            return true;
        }
        final NubiaProjectionPackageLease lease = lease();
        mTransports = Set.copyOf(targets);
        if (targets.isEmpty()) { mSessionEnabled = null; }
        try {
            // Caption acquisition reads the vendor provider. Restore its package
            // before changing transport ownership, then block its focusable shield.
            lease.release();
            if (!targets.isEmpty() && mSessionEnabled == null) {
                mSessionEnabled = isEnabled();
            }
            mLastSuccess = NubiaCaptionVisibilityManager.setTransports(targets);
            if (!targets.isEmpty() && Boolean.TRUE.equals(mSessionEnabled)) {
                lease.acquire();
            }
            mError = "";
            return mLastSuccess;
        } catch (IOException | RuntimeException error) {
            mLastSuccess = false;
            mError = error.toString();
            CompatibilityDiagnostics.record("NUBIA-PROJECTION-001",
                    "Could not update Nubia projection protection", mError);
            return false;
        }
    }

    synchronized void recover() {
        // A reconnect in this process must not undo a live session's ownership.
        if (mTransports.isEmpty()) { setTransports(Set.of()); }
    }

    synchronized String diagnosticSummary() {
        return "configured=" + isEnabled() + ", active=" + mSessionEnabled
                + ", transports=" + mTransports
                + ", restorePending=" + preferences().contains(RESTORE)
                + ", error=" + (mError.isEmpty() ? "none" : mError);
    }

    private static SharedPreferences preferences() {
        return MagicDeskApplication.applicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static NubiaProjectionPackageLease lease() {
        final Context context = MagicDeskApplication.applicationContext();
        final SharedPreferences preferences = preferences();
        return new NubiaProjectionPackageLease(new NubiaProjectionPackageLease.Backend() {
            @Override public int readState() {
                return context.getPackageManager().getApplicationEnabledSetting(PACKAGE);
            }
            @Override public void writeState(final int state) throws IOException {
                final String command = switch (state) {
                    case NubiaProjectionPackageLease.DEFAULT -> "default-state";
                    case NubiaProjectionPackageLease.ENABLED -> "enable";
                    case NubiaProjectionPackageLease.DISABLED_USER -> "disable-user";
                    default -> throw new IOException("invalid projection restore state: " + state);
                };
                ShellAccess.run("pm " + command + " --user "
                        + (android.os.Process.myUid() / 100_000) + " " + PACKAGE);
            }
            @Override public Integer readRestoreState() {
                return preferences.contains(RESTORE) ? preferences.getInt(RESTORE, 0) : null;
            }
            @Override public void saveRestoreState(final Integer state) throws IOException {
                final SharedPreferences.Editor editor = preferences.edit();
                if (state == null) { editor.remove(RESTORE); }
                else { editor.putInt(RESTORE, state); }
                if (!editor.commit()) { throw new IOException("could not journal projection package state"); }
            }
        });
    }
}
