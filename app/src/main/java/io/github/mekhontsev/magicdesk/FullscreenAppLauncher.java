package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

final class FullscreenAppLauncher {
    private static final String AM = "/system/bin/am";

    private FullscreenAppLauncher() {
    }

    static void launch(final Intent intent, final int displayId) throws IOException {
        final ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null || displayId < 0) {
            throw new IOException("invalid fullscreen launch request");
        }
        final String output = ShellAccess.run(createLaunchCommand(
                component.getPackageName(),
                component.getClassName(),
                intent.getAction(),
                intent.getCategories(),
                displayId,
                intent.getFlags()));
        if (commandFailed(output)) {
            throw new IOException(output.trim());
        }
    }

    static String createLaunchCommand(
            final String packageName,
            final String className,
            final String action,
            final Set<String> categories,
            final int displayId,
            final int flags) {
        if (!PackageNameValidator.isSafe(packageName)
                || !isSafeClassName(className)
                || !isSafeIntentName(action)
                || displayId < 0) {
            throw new IllegalArgumentException("invalid fullscreen launch target");
        }
        final String componentClass = className.startsWith(packageName + ".")
                ? className.substring(packageName.length()) : className;
        final StringBuilder command = new StringBuilder(
                AM + " start --user 0 --display " + displayId
                + " --windowingMode 1"
                + " -f 0x" + Integer.toHexString(flags)
                + " -a " + action);
        if (categories != null && !categories.isEmpty()) {
            final ArrayList<String> sortedCategories =
                    new ArrayList<>(categories);
            Collections.sort(sortedCategories);
            for (final String category : sortedCategories) {
                if (!isSafeIntentName(category)) {
                    throw new IllegalArgumentException(
                            "invalid fullscreen launch category");
                }
                command.append(" -c ").append(category);
            }
        }
        return command
                .append(" --ez start_from_heartservice_app_lock true")
                .append(" -n ")
                .append(packageName)
                .append('/')
                .append(componentClass)
                .toString();
    }

    static boolean commandFailed(final String output) {
        return output != null
                && (output.contains("Error:")
                        || output.contains("Permission Denial")
                        || output.contains("Exception"));
    }

    private static boolean isSafeClassName(final String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (int index = 0; index < className.length(); index++) {
            final char value = className.charAt(index);
            if (!(Character.isLetterOrDigit(value)
                    || value == '.'
                    || value == '_'
                    || value == '$')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeIntentName(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '.'
                    || character == '_'
                    || character == '$')) {
                return false;
            }
        }
        return true;
    }
}
