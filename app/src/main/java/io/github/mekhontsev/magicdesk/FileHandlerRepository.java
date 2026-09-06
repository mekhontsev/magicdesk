package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Blocking handler discovery and system-default persistence, without UI ownership. */
final class FileHandlerRepository {
    private FileHandlerRepository() {
    }

    static Selection load(
            final Context context, final Intent source, final boolean alwaysAsk) {
        final PackageManager packages = context.getPackageManager();
        final List<Target> targets = queryAndroidTargets(packages, source);
        final Target preferred = preferredTarget(packages, source, targets);
        if (alwaysAsk || preferred == null) {
            addDesktopTargets(context, source.getType(), targets);
            targets.sort(Comparator
                    .comparing((Target target) -> target.label,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Target::key));
        }
        return new Selection(targets, preferred);
    }

    static void setPreferred(
            final Intent source, final List<Target> targets, final Target selected)
            throws IOException {
        final List<String> components = new ArrayList<>();
        int bestMatch = 0;
        for (final Target target : targets) {
            if (target.android()) {
                components.add(target.component.flattenToString());
                bestMatch = Math.max(bestMatch, target.match);
            }
        }
        ShellAccess.setPreferredFileHandler(source.getType(),
                components.toArray(new String[0]),
                selected.component.flattenToString(), bestMatch);
    }

    private static List<Target> queryAndroidTargets(
            final PackageManager packages, final Intent source) {
        final List<ResolveInfo> matches = packages.queryIntentActivities(
                source, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        final Map<ComponentName, Target> unique = new LinkedHashMap<>();
        for (final ResolveInfo match : matches) {
            final ActivityInfo activity = match.activityInfo;
            if (activity == null || !activity.exported) {
                continue;
            }
            final ComponentName component = new ComponentName(
                    activity.packageName, activity.name);
            unique.put(component, new Target(component, null,
                    String.valueOf(match.loadLabel(packages)), activity.packageName,
                    loadIcon(packages, match), match.match));
        }
        return new ArrayList<>(unique.values());
    }

    private static void addDesktopTargets(
            final Context context, final String mimeType, final List<Target> targets) {
        try {
            for (final DesktopApplicationRepository.Entry handler
                    : DesktopApplicationRepository.queryHandlers(mimeType)) {
                final DesktopApplicationShortcut shortcut = handler.shortcut;
                final int detailsResource = shortcut.execBackend == DesktopExecBackend.TERMUX
                        ? R.string.file_manager_termux_command : R.string.file_manager_shell_command;
                targets.add(new Target(null, handler, shortcut.name,
                        context.getString(detailsResource),
                        DesktopApplicationIconResolver.resolve(context, shortcut), 0));
            }
        } catch (IOException ignored) {
            // Android handlers remain usable when shell lookup is absent.
        }
    }

    private static Target preferredTarget(
            final PackageManager packages, final Intent source, final List<Target> targets) {
        final ResolveInfo resolved = packages.resolveActivity(
                source, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        if (resolved == null || resolved.activityInfo == null) {
            return null;
        }
        final ComponentName component = new ComponentName(
                resolved.activityInfo.packageName, resolved.activityInfo.name);
        for (final Target target : targets) {
            if (component.equals(target.component)) {
                return target;
            }
        }
        try {
            final String encoded = ShellAccess.getSelectedFileHandler(
                    source.getType(), source.getDataString());
            final ComponentName selected = encoded == null
                    ? null : ComponentName.unflattenFromString(encoded);
            if (selected != null) {
                for (final Target target : targets) {
                    if (selected.equals(target.component)) {
                        return target;
                    }
                }
            }
        } catch (IOException ignored) {
            // The custom chooser remains usable when shell lookup is absent.
        }
        return null;
    }

    private static Drawable loadIcon(final PackageManager packages, final ResolveInfo match) {
        try {
            return match.loadIcon(packages);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static final class Selection {
        final List<Target> targets;
        final Target preferred;

        Selection(final List<Target> targets, final Target preferred) {
            this.targets = List.copyOf(targets);
            this.preferred = preferred;
        }

        Target directTarget(final boolean alwaysAsk) {
            if (alwaysAsk) {
                return null;
            }
            if (preferred != null) {
                return preferred;
            }
            return targets.size() == 1 ? targets.get(0) : null;
        }
    }

    static final class Target {
        final ComponentName component;
        final DesktopApplicationRepository.Entry desktopHandler;
        final String label;
        final String details;
        final Drawable icon;
        final int match;

        Target(final ComponentName component,
                final DesktopApplicationRepository.Entry desktopHandler,
                final String label, final String details, final Drawable icon, final int match) {
            this.component = component;
            this.desktopHandler = desktopHandler;
            this.label = label;
            this.details = details;
            this.icon = icon;
            this.match = match;
        }

        boolean android() {
            return component != null;
        }

        String key() {
            return android() ? component.flattenToString() : desktopHandler.desktopFilePath;
        }
    }
}
