package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Explicit task-instance semantics for one desktop Activity launch. */
enum DesktopTaskInstancePolicy {
    REUSE_EXISTING("reuse"),
    CREATE_NEW("new");

    final String wireName;

    DesktopTaskInstancePolicy(final String wireName) {
        this.wireName = wireName;
    }

    static DesktopTaskInstancePolicy parse(final String value) {
        for (final DesktopTaskInstancePolicy policy : values()) {
            if (policy.wireName.equalsIgnoreCase(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("instance must be reuse or new");
    }

    Intent applyTo(final PackageManager packageManager, final Intent source) {
        if (source == null) {
            throw new IllegalArgumentException("launch Intent is required");
        }
        if (this == CREATE_NEW) {
            requireNewWindowSupport(packageManager, source);
        }
        final Intent intent = new Intent(source);
        intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if (this == CREATE_NEW) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        return intent;
    }

    private static void requireNewWindowSupport(
            final PackageManager packageManager, final Intent intent) {
        final ActivityInfo info;
        try {
            if (intent.getComponent() != null) {
                info = packageManager.getActivityInfo(intent.getComponent(),
                        PackageManager.ComponentInfoFlags.of(0));
            } else {
                final ResolveInfo resolved = packageManager.resolveActivity(intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
                info = resolved == null ? null : resolved.activityInfo;
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalArgumentException("Activity is unavailable", error);
        }
        if (info == null) {
            throw new IllegalArgumentException("Activity is unavailable");
        }
        if (!supportsNewWindow(info.launchMode, info.documentLaunchMode)) {
            final ComponentName component = intent.getComponent() != null
                    ? intent.getComponent() : new ComponentName(info.packageName, info.name);
            ApplicationTaskPlacement.rejectExistingInstance(LaunchActivityIdentity.resolve(
                    FrameworkUserApi.userId(android.os.Process.myUserHandle()),
                    packageManager, component));
        }
    }

    static boolean supportsNewWindow(final int launchMode, final int documentLaunchMode) {
        return launchMode != ActivityInfo.LAUNCH_SINGLE_TASK
                && launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
                && documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NEVER;
    }
}
