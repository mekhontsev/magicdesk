package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.net.URISyntaxException;

/** Immutable Android task portion of a desktop launch request. */
final class AndroidLaunchSpec {
    enum Kind {
        DEFAULT,
        INTENT
    }

    final Kind kind;
    final AppLaunchTarget target;
    final String intentUri;

    private AndroidLaunchSpec(
            final Kind kind,
            final AppLaunchTarget target,
            final String intentUri) {
        if (kind == null || target == null) {
            throw new IllegalArgumentException("missing Android launch target");
        }
        if (kind == Kind.INTENT
                && (intentUri == null || intentUri.isEmpty())) {
            throw new IllegalArgumentException("missing Android launch Intent");
        }
        this.kind = kind;
        this.target = target;
        this.intentUri = intentUri == null ? "" : intentUri;
    }

    static AndroidLaunchSpec defaultLaunch(
            final AppLaunchTarget target) {
        return new AndroidLaunchSpec(Kind.DEFAULT, target, "");
    }

    static AndroidLaunchSpec intent(
            final AppLaunchTarget target, final String intentUri) {
        return new AndroidLaunchSpec(Kind.INTENT, target, intentUri);
    }

    Intent resolve(final PackageManager packageManager) {
        if (kind == Kind.DEFAULT) {
            return target.resolve(packageManager);
        }
        final Intent intent;
        try {
            intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException error) {
            return null;
        }
        if (intent.getComponent() == null) {
            if (!target.activityClassName.isEmpty()) {
                intent.setClassName(
                        target.packageName,
                        target.activityClassName);
            } else if (intent.getPackage() == null) {
                intent.setPackage(target.packageName);
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
