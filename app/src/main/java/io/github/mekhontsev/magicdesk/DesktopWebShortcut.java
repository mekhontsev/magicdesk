package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.net.URI;
import java.net.URISyntaxException;

/** Type=Link entry that opens an HTTP(S) resource. */
final class DesktopWebShortcut extends DesktopEntry {
    private static final String DEFAULT_ICON = "web-browser";
    private static final int MAX_URL_LENGTH = 8192;

    final String url;

    DesktopWebShortcut(
            final String name,
            final String icon,
            final String url) {
        super(name, icon == null || icon.isEmpty() ? DEFAULT_ICON : icon, "");
        this.url = normalizeUrl(url);
    }

    Intent createViewIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    DesktopApplicationShortcut resolveApplicationShortcut(
            final PackageManager packageManager) {
        if (packageManager == null) {
            return null;
        }
        final Intent intent = createViewIntent();
        final ResolveInfo resolved = packageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null
                || isSystemResolver(resolved.activityInfo.name)) {
            return null;
        }
        final String packageName = resolved.activityInfo.packageName;
        if (packageManager.getLaunchIntentForPackage(packageName) == null) {
            return null;
        }
        intent.setClassName(packageName, resolved.activityInfo.name);
        final AppLaunchTarget target =
                AppLaunchTarget.packageDefault(packageName);
        return new DesktopApplicationShortcut(
                name,
                icon,
                "",
                target,
                intent.toUri(Intent.URI_INTENT_SCHEME),
                DesktopLaunchMode.AUTO,
                false,
                DesktopExecBackend.SHELL,
                false);
    }

    static String normalizeUrl(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing web shortcut URL");
        }
        final String trimmed = value.trim();
        if (trimmed.length() == 0 || trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("invalid web shortcut URL");
        }
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException | RuntimeException error) {
            throw new IllegalArgumentException("invalid web shortcut URL", error);
        }
        final String scheme = uri.getScheme();
        if (uri.isOpaque()
                || scheme == null
                || !("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("unsupported web shortcut URL");
        }
        return uri.toASCIIString();
    }

    private static boolean isSystemResolver(final String className) {
        return className != null
                && (className.endsWith("ResolverActivity")
                        || className.endsWith("ChooserActivity"));
    }
}
