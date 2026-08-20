package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.net.URISyntaxException;

/** Type=Application entry with an Android launch descriptor. */
final class DesktopApplicationShortcut extends DesktopEntry {
    final AppLaunchTarget launchTarget;
    final String intentUri;
    final DesktopLaunchMode launchMode;
    final boolean defaultLaunch;
    final DesktopExecBackend execBackend;
    final boolean terminal;

    DesktopApplicationShortcut(
            final String name,
            final String icon,
            final String exec,
            final AppLaunchTarget launchTarget,
            final String intentUri,
            final DesktopLaunchMode launchMode,
            final boolean defaultLaunch,
            final DesktopExecBackend execBackend,
            final boolean terminal) {
        super(name, icon, exec);
        if ((intentUri == null || intentUri.isEmpty()) && this.exec.isEmpty()) {
            throw new IllegalArgumentException(
                    "application entry has no Intent or Exec");
        }
        this.launchTarget = launchTarget;
        this.intentUri = intentUri == null ? "" : intentUri;
        this.launchMode = launchMode == null
                ? DesktopLaunchMode.AUTO : launchMode;
        if (defaultLaunch && launchTarget == null) {
            throw new IllegalArgumentException(
                    "default launch requires an application target");
        }
        this.defaultLaunch = defaultLaunch;
        this.execBackend = execBackend == null
                ? DesktopExecBackend.SHELL : execBackend;
        this.terminal = terminal;
        if (hasExecLaunch()) {
            DesktopExecCommand.normalize(this.exec);
        }
    }

    boolean hasIntentLaunch() {
        return !intentUri.isEmpty();
    }

    boolean hasExecLaunch() {
        return !exec.isEmpty() && !hasIntentLaunch() && !defaultLaunch;
    }

    Intent resolveIntent(final PackageManager packageManager) {
        if (intentUri.isEmpty()) {
            return null;
        }
        final Intent intent;
        try {
            intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException error) {
            return null;
        }
        if (intent.getComponent() == null && launchTarget != null) {
            if (!launchTarget.activityClassName.isEmpty()) {
                intent.setClassName(
                        launchTarget.packageName,
                        launchTarget.activityClassName);
            } else if (intent.getPackage() == null) {
                intent.setPackage(launchTarget.packageName);
            }
        }
        if (intent.getComponent() == null && packageManager != null) {
            final ResolveInfo resolved = packageManager.resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null) {
                intent.setComponent(new ComponentName(
                        resolved.activityInfo.packageName,
                        resolved.activityInfo.name));
            }
        }
        return intent.getComponent() == null ? null : intent;
    }
}
