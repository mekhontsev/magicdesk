package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Reusable tool launches, without borrowed task, display, URI grant or session identities. */
final class BuiltInRecentLaunch {
    static DesktopApplicationShortcut describe(Context context, Intent source, AppLaunchTarget target) {
        final var entry = BuiltInDesktopAppCatalog.searchEntries().stream()
                .filter(item -> item.launchTarget.activityClassName.equals(target.activityClassName))
                .findFirst().orElse(null);
        if (entry == null) return null;
        if (target.activityClassName.equals(CommandConsoleActivity.class.getName())) {
            return CommandConsoleActivity.recentLaunch(context, source);
        }
        return new DesktopApplicationShortcut(context.getString(entry.fallbackLabelResId), "", "",
                entry.launchTarget, "", DesktopLaunchMode.AUTO, true, DesktopExecBackend.SHELL, false);
    }

    static boolean usesTermux(DesktopApplicationShortcut shortcut) {
        if (shortcut.launchTarget == null || !BuildConfig.APPLICATION_ID.equals(shortcut.launchTarget.packageName)
                || !CommandConsoleActivity.class.getName().equals(shortcut.launchTarget.activityClassName)
                || shortcut.intentUri.isEmpty()) return false;
        try {
            return CommandConsoleActivity.isTermux(Intent.parseUri(shortcut.intentUri, Intent.URI_INTENT_SCHEME));
        } catch (java.net.URISyntaxException error) { return false; }
    }

    private BuiltInRecentLaunch() { }
}
