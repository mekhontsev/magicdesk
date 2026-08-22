package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads stable launcher shortcuts directly from an application's manifest. */
final class AppShortcutRepository {
    private static final String TAG = "MagicDeskShortcuts";
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    private static final String SHORTCUTS_METADATA = "android.app.shortcuts";
    private static final int MAX_ACTIONS = 4;

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
        return load(app.packageName, app.launchTarget);
    }

    List<AppShortcutAction> load(final AppLaunchTarget target) {
        return target == null
                ? java.util.Collections.emptyList()
                : load(target.packageName, target);
    }

    private List<AppShortcutAction> load(
            final String packageName,
            final AppLaunchTarget target) {
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
                        : parse(packageName, resources, parser);
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

    private List<AppShortcutAction> parse(
            final String publisherPackage,
            final Resources resources,
            final XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        final List<AppShortcutAction> actions = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        ParsedShortcut current = null;
        int shortcutDepth = -1;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                && actions.size() < MAX_ACTIONS) {
            final String name = parser.getName();
            if (event == XmlPullParser.START_TAG
                    && "shortcut".equals(name)) {
                current = readShortcut(resources, parser);
                shortcutDepth = parser.getDepth();
                continue;
            }
            if (current != null
                    && event == XmlPullParser.START_TAG
                    && parser.getDepth() == shortcutDepth + 1
                    && "intent".equals(name)) {
                current.intents.add(Intent.parseIntent(
                        resources, parser, parser));
                continue;
            }
            if (current != null
                    && event == XmlPullParser.END_TAG
                    && parser.getDepth() == shortcutDepth
                    && "shortcut".equals(name)) {
                final AppShortcutAction action = finish(
                        publisherPackage, current);
                if (action != null && ids.add(action.id)) {
                    actions.add(action);
                }
                current = null;
                shortcutDepth = -1;
            }
        }
        return actions;
    }

    private ParsedShortcut readShortcut(
            final Resources resources,
            final XmlResourceParser parser) {
        final String id = attributeText(resources, parser, "shortcutId");
        final String label = attributeText(
                resources, parser, "shortcutShortLabel");
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
        return new ParsedShortcut(id, label, icon, enabled);
    }

    private AppShortcutAction finish(
            final String publisherPackage,
            final ParsedShortcut shortcut) {
        if (!shortcut.enabled
                || shortcut.id.isEmpty()
                || shortcut.label.isEmpty()
                || shortcut.intents.size() != 1) {
            return null;
        }
        final Intent intent = shortcut.intents.get(0);
        final ComponentName component = resolveShortcutComponent(
                publisherPackage, intent);
        if (component == null) {
            return null;
        }
        intent.setComponent(component);
        return new AppShortcutAction(
                shortcut.id,
                shortcut.label,
                shortcut.icon,
                intent);
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
        final String label;
        final Drawable icon;
        final boolean enabled;
        final List<Intent> intents = new ArrayList<>();

        ParsedShortcut(
                final String id,
                final String label,
                final Drawable icon,
                final boolean enabled) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.enabled = enabled;
        }
    }
}
