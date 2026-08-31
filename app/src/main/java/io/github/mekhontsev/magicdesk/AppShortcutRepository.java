package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads launcher-published shortcuts and enriches them with manifest icons. */
final class AppShortcutRepository {
    private static final String TAG = "MagicDeskShortcuts";
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    private static final String SHORTCUTS_METADATA = "android.app.shortcuts";
    private static final int MAX_ACTIONS = 4;
    private static final int MAX_DISCOVERED_ACTIONS = 64;

    private final PackageManager mPackageManager;
    private final int mDensityDpi;

    AppShortcutRepository(final Context context) {
        mPackageManager = context.getPackageManager();
        mDensityDpi = context.getResources()
                .getDisplayMetrics().densityDpi;
    }

    List<AppShortcutAction> load(final AppItem app) {
        if (app == null) {
            return java.util.Collections.emptyList();
        }
        return load(app.launchTarget);
    }

    List<AppShortcutAction> load(final AppLaunchTarget target) {
        return target == null
                ? java.util.Collections.emptyList()
                : trim(loadAll(target), MAX_ACTIONS);
    }

    List<AppShortcutAction> loadAll(final AppLaunchTarget target) {
        if (target == null) {
            return java.util.Collections.emptyList();
        }
        final List<ManifestShortcut> manifest = loadManifest(
                target.packageName, target, MAX_DISCOVERED_ACTIONS);
        return loadPublished(target, manifest);
    }

    private List<AppShortcutAction> loadPublished(
            final AppLaunchTarget target,
            final List<ManifestShortcut> manifest) {
        try {
            final ShortcutInfo[] shortcuts = ShellAccess.queryAppShortcuts(
                    target.packageName);
            if (shortcuts == null || shortcuts.length == 0) {
                return java.util.Collections.emptyList();
            }
            final Map<String, Drawable> manifestIcons = new HashMap<>();
            for (final ManifestShortcut shortcut : manifest) {
                manifestIcons.put(shortcut.id, shortcut.icon);
            }
            final List<AppShortcutAction> actions = new ArrayList<>();
            final Set<String> ids = new HashSet<>();
            for (final ShortcutInfo shortcut : shortcuts) {
                if (actions.size() >= MAX_DISCOVERED_ACTIONS
                        || shortcut == null
                        || !shortcut.isEnabled()
                        || !ids.add(shortcut.getId())) {
                    continue;
                }
                final Intent[] intents = shortcut.getIntents();
                final Intent intent = intents == null || intents.length == 0
                        ? shortcut.getIntent()
                        : intents[intents.length - 1];
                final ComponentName component = intent == null
                        ? shortcut.getActivity()
                        : resolveShortcutComponent(
                                target.packageName, intent);
                final CharSequence label = shortcut.getShortLabel();
                if (label == null || label.length() == 0) {
                    continue;
                }
                actions.add(new AppShortcutAction(
                        shortcut.getId(),
                        label.toString(),
                        manifestIcons.get(shortcut.getId()),
                        shortcut.getPackage(),
                        shortcut.getUserHandle(),
                        component,
                        intent == null ? "" : intent.getAction(),
                        shortcutSource(shortcut)));
            }
            return actions;
        } catch (IOException | SecurityException | IllegalStateException error) {
            Log.i(TAG, "Published shortcuts are not available", error);
            return java.util.Collections.emptyList();
        }
    }

    private List<ManifestShortcut> loadManifest(
            final String packageName,
            final AppLaunchTarget target,
            final int limit) {
        final ComponentName launcher = resolveLauncher(target);
        if (launcher == null) {
            return java.util.Collections.emptyList();
        }
        try {
            final ActivityInfo activityInfo = mPackageManager.getActivityInfo(
                    launcher,
                    PackageManager.GET_META_DATA);
            final Resources resources = mPackageManager
                    .getResourcesForApplication(activityInfo.applicationInfo);
            try (XmlResourceParser parser = activityInfo.loadXmlMetaData(
                    mPackageManager, SHORTCUTS_METADATA)) {
                return parser == null
                        ? java.util.Collections.emptyList()
                        : parse(resources, parser, limit);
            }
        } catch (PackageManager.NameNotFoundException
                | IOException
                | XmlPullParserException
                | RuntimeException error) {
            Log.w(TAG, "Cannot read shortcuts for " + packageName, error);
            return java.util.Collections.emptyList();
        }
    }

    private ComponentName resolveLauncher(final AppLaunchTarget target) {
        final Intent intent = target == null
                ? null : target.resolve(mPackageManager);
        if (intent == null) {
            return null;
        }
        if (intent.getComponent() != null) {
            return intent.getComponent();
        }
        final ResolveInfo resolved = mPackageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved == null || resolved.activityInfo == null
                ? null
                : new ComponentName(
                        resolved.activityInfo.packageName,
                        resolved.activityInfo.name);
    }

    private List<ManifestShortcut> parse(
            final Resources resources,
            final XmlResourceParser parser,
            final int limit)
            throws IOException, XmlPullParserException {
        final List<ManifestShortcut> shortcuts = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        ParsedShortcut current = null;
        int shortcutDepth = -1;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && shortcuts.size() < limit) {
            final String name = parser.getName();
            if (event == XmlPullParser.START_TAG
                    && "shortcut".equals(name)) {
                current = readShortcut(resources, parser);
                shortcutDepth = parser.getDepth();
                continue;
            }
            if (current != null
                    && event == XmlPullParser.END_TAG
                    && parser.getDepth() == shortcutDepth
                    && "shortcut".equals(name)) {
                final ManifestShortcut shortcut = finish(current);
                if (shortcut != null && ids.add(shortcut.id)) {
                    shortcuts.add(shortcut);
                }
                current = null;
                shortcutDepth = -1;
            }
        }
        return shortcuts;
    }

    private ParsedShortcut readShortcut(
            final Resources resources,
            final XmlResourceParser parser) {
        final String id = attributeText(resources, parser, "shortcutId");
        final boolean enabled = parser.getAttributeBooleanValue(
                ANDROID_NAMESPACE, "enabled", true);
        Drawable icon = null;
        final int iconId = parser.getAttributeResourceValue(
                ANDROID_NAMESPACE, "icon", 0);
        if (iconId != 0) {
            try {
                icon = resources.getDrawableForDensity(
                        iconId, mDensityDpi, null);
            } catch (Resources.NotFoundException ignored) {
                // A broken optional icon must not hide an otherwise valid action.
            }
        }
        return new ParsedShortcut(id, icon, enabled);
    }

    private static ManifestShortcut finish(final ParsedShortcut shortcut) {
        if (!shortcut.enabled
                || shortcut.id.isEmpty()) {
            return null;
        }
        return new ManifestShortcut(shortcut.id, shortcut.icon);
    }

    private ComponentName resolveShortcutComponent(
            final String publisherPackage,
            final Intent intent) {
        final ComponentName explicit = intent.getComponent();
        if (explicit != null) {
            return publisherPackage.equals(explicit.getPackageName())
                    ? explicit : null;
        }
        final Intent scoped = new Intent(intent).setPackage(publisherPackage);
        final ResolveInfo resolved = mPackageManager.resolveActivity(
                scoped, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null
                || resolved.activityInfo == null
                || !publisherPackage.equals(
                        resolved.activityInfo.packageName)) {
            return null;
        }
        return new ComponentName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name);
    }

    private static String attributeText(
            final Resources resources,
            final XmlResourceParser parser,
            final String name) {
        final int resourceId = parser.getAttributeResourceValue(
                ANDROID_NAMESPACE, name, 0);
        if (resourceId != 0) {
            try {
                final CharSequence value = resources.getText(resourceId);
                return value == null ? "" : value.toString().trim();
            } catch (Resources.NotFoundException ignored) {
                return "";
            }
        }
        final String value = parser.getAttributeValue(
                ANDROID_NAMESPACE, name);
        return value == null ? "" : value.trim();
    }

    private static final class ParsedShortcut {
        final String id;
        final Drawable icon;
        final boolean enabled;

        ParsedShortcut(
                final String id,
                final Drawable icon,
                final boolean enabled) {
            this.id = id;
            this.icon = icon;
            this.enabled = enabled;
        }
    }

    private static final class ManifestShortcut {
        final String id;
        final Drawable icon;

        ManifestShortcut(final String id, final Drawable icon) {
            this.id = id;
            this.icon = icon;
        }
    }

    private static List<AppShortcutAction> trim(
            final List<AppShortcutAction> actions,
            final int limit) {
        return actions.size() <= limit
                ? actions
                : new ArrayList<>(actions.subList(0, limit));
    }

    private static String shortcutSource(final ShortcutInfo shortcut) {
        if (shortcut.isDynamic()) {
            return "dynamic";
        }
        if (shortcut.isDeclaredInManifest()) {
            return "manifest";
        }
        if (shortcut.isPinned()) {
            return "pinned";
        }
        if (shortcut.isCached()) {
            return "cached";
        }
        return "published";
    }
}
