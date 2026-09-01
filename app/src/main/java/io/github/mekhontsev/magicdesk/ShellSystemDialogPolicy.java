package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Classifies transient framework UI without including all system apps. */
final class ShellSystemDialogPolicy {
    private static final String TAG = "MagicDeskInputWindows";
    private static final String ANDROID_PACKAGE = "android";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private final Set<String> mPackages;

    ShellSystemDialogPolicy(final Set<String> packages) {
        mPackages = packages == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(packages));
    }

    static ShellSystemDialogPolicy create(
            final PackageManager packageManager) {
        final Set<String> packages = new LinkedHashSet<>();
        packages.add(ANDROID_PACKAGE);
        packages.add(SYSTEM_UI_PACKAGE);
        if (packageManager == null) {
            return new ShellSystemDialogPolicy(packages);
        }
        try {
            final Intent payload = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain");
            final ResolveInfo chooser = packageManager.resolveActivity(
                    Intent.createChooser(payload, null),
                    PackageManager.MATCH_SYSTEM_ONLY);
            final ActivityInfo activity = chooser == null
                    ? null : chooser.activityInfo;
            addPackage(packages,
                    activity == null ? null : activity.packageName);
        } catch (RuntimeException error) {
            Log.w(TAG, "could not resolve framework chooser", error);
        }
        return new ShellSystemDialogPolicy(packages);
    }

    boolean isSystemDialog(
            final FrameworkInputWindowState.Window window) {
        return window != null && mPackages.contains(window.packageName);
    }

    private static void addPackage(
            final Set<String> packages,
            final String packageName) {
        if (PackageNameValidator.isSafe(packageName)) {
            packages.add(packageName);
        }
    }
}
