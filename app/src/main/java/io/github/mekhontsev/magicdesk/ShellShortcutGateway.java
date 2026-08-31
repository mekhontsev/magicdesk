package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;
import android.os.Process;

import java.util.Collections;
import java.util.List;

/** Typed shell-identity adapter for Android's published shortcut service. */
final class ShellShortcutGateway {
    private static final int QUERY_FLAGS =
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;

    private ShellShortcutGateway() {
    }

    static ShortcutInfo[] query(
            final Context context,
            final String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("shortcut package is required");
        }
        final LauncherApps launcherApps = launcherApps(context);
        final LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                .setPackage(packageName.trim())
                .setQueryFlags(QUERY_FLAGS);
        final List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(
                query, Process.myUserHandle());
        return shortcuts == null
                ? new ShortcutInfo[0]
                : shortcuts.toArray(new ShortcutInfo[0]);
    }

    static ShortcutInfo require(
            final Context context,
            final String packageName,
            final String shortcutId,
            final UserHandle user) {
        if (packageName == null || packageName.trim().isEmpty()
                || shortcutId == null || shortcutId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "shortcut package and id are required");
        }
        if (user == null) {
            throw new IllegalArgumentException("shortcut user is required");
        }
        final LauncherApps launcherApps = launcherApps(context);
        final LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                .setPackage(packageName.trim())
                .setShortcutIds(Collections.singletonList(shortcutId))
                .setQueryFlags(QUERY_FLAGS);
        final List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(
                query, user);
        if (shortcuts == null || shortcuts.isEmpty()
                || shortcuts.get(0) == null
                || !shortcuts.get(0).isEnabled()) {
            throw new IllegalStateException(
                    "published shortcut is unavailable: " + shortcutId);
        }
        return shortcuts.get(0);
    }

    static ComponentName targetComponent(final ShortcutInfo shortcut) {
        if (shortcut == null) {
            return null;
        }
        final Intent[] intents = shortcut.getIntents();
        if (intents != null) {
            for (int index = intents.length - 1; index >= 0; index--) {
                if (intents[index] != null
                        && intents[index].getComponent() != null) {
                    return intents[index].getComponent();
                }
            }
        }
        return shortcut.getActivity();
    }

    static PendingIntent launchIntent(
            final Context context,
            final ShortcutInfo shortcut) {
        if (shortcut == null) {
            throw new IllegalArgumentException("shortcut is required");
        }
        final PendingIntent pendingIntent = launcherApps(context)
                .getShortcutIntent(
                shortcut.getPackage(),
                shortcut.getId(),
                null,
                shortcut.getUserHandle());
        if (pendingIntent == null) {
            throw new IllegalStateException(
                    "published shortcut has no launch token: "
                            + shortcut.getId());
        }
        return pendingIntent;
    }

    private static LauncherApps launcherApps(final Context context) {
        try {
            final LauncherApps launcherApps = ShellIdentityContext.create(context)
                    .getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                throw new IllegalStateException(
                        "Android shortcut service is unavailable");
            }
            return launcherApps;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(
                    "Android shell package is unavailable", error);
        }
    }
}
