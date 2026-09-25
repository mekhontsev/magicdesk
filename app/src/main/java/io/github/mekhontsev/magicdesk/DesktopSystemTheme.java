package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;

/** App-process owner; recovery never starts Desktop or acquires privileges. */
final class DesktopSystemTheme {
    private static DesktopSystemTheme sInstance;
    private final DesktopSystemThemeSession mSession;
    private volatile String mStatus = "idle";

    private DesktopSystemTheme(final Context context) {
        mSession = new DesktopSystemThemeSession(new DesktopSystemThemeSession.Access() {
            @Override public SystemNightMode read() throws IOException { return ShellAccess.readSystemNightMode(); }
            @Override public void write(final SystemNightMode mode) throws IOException { ShellAccess.setSystemNightMode(mode); }
        }, new Journal(context));
        final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(final boolean selfChange) {
                TaskCommandQueue.execute(() -> run(() -> mSession.systemChanged()));
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("ui_night_mode"), false, observer);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("ui_night_mode_custom_type"), false, observer);
    }

    static void initialize(final Context context) {
        sInstance = new DesktopSystemTheme(context);
        ShellAccess.addStateListener(state -> {
            if (state.isReady()) refresh();
        });
    }

    static void refresh() {
        final DesktopSystemTheme owner = sInstance;
        if (owner == null) return;
        TaskCommandQueue.execute(() -> owner.run(() -> {
            final boolean active = DesktopRuntimeBridge.hasWorkspaces();
            final DesktopSystemThemeSession.Preference preference = active
                    ? MagicDeskSettings.load().systemTheme
                    : DesktopSystemThemeSession.Preference.UNCHANGED;
            if (owner.mSession.update(preference, active)) {
                owner.mStatus = active ? "requested=" + preference : "idle";
            }
        }));
    }

    static String diagnostics() { return sInstance == null ? "idle" : sInstance.mStatus; }

    private interface Operation { void run() throws IOException; }

    private void run(final Operation operation) {
        if (!ShellAccess.isReady()) return;
        try { operation.run(); }
        catch (IOException | RuntimeException error) {
            final String status = "error=" + ShellAccess.usefulMessage(error);
            if (!status.equals(mStatus)) {
                CompatibilityDiagnostics.record("DESKTOP-THEME-001",
                        "Could not update the temporary system theme", status, error);
            }
            mStatus = status;
        }
    }

    private static final class Journal implements DesktopSystemThemeSession.Storage {
        private final SharedPreferences mPreferences;

        Journal(final Context context) {
            mPreferences = context.getSharedPreferences("magicdesk_system_theme", Context.MODE_PRIVATE);
        }

        @Override public DesktopSystemThemeSession.Override read() throws IOException {
            if (!mPreferences.contains("previous")) return null;
            try {
                return new DesktopSystemThemeSession.Override(
                        SystemNightMode.valueOf(mPreferences.getString("previous", "")),
                        SystemNightMode.valueOf(mPreferences.getString("applied", "")));
            } catch (IllegalArgumentException | ClassCastException error) {
                throw new IOException("Invalid system-theme recovery record", error);
            }
        }

        @Override @SuppressLint("ApplySharedPref")
        public void write(final DesktopSystemThemeSession.Override value) throws IOException {
            final SharedPreferences.Editor editor = mPreferences.edit().clear();
            if (value != null) {
                editor.putString("previous", value.previous().name())
                        .putString("applied", value.applied().name());
            }
            if (!editor.commit()) throw new IOException("Could not persist system-theme recovery record");
        }
    }
}
