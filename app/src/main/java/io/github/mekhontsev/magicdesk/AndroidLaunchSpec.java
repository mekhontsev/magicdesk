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
    private final Intent mIntent;
    private final String mIntentUri;

    private AndroidLaunchSpec(
            final Kind kind,
            final AppLaunchTarget target,
            final Intent intent,
            final String intentUri) {
        if (kind == null || (kind == Kind.DEFAULT && target == null)) {
            throw new IllegalArgumentException("missing Android launch target");
        }
        if (kind == Kind.INTENT
                && intent == null
                && (intentUri == null || intentUri.isEmpty())) {
            throw new IllegalArgumentException("missing Android launch Intent");
        }
        this.kind = kind;
        this.target = target;
        mIntent = intent == null ? null : new Intent(intent);
        mIntentUri = intentUri == null ? "" : intentUri;
    }

    static AndroidLaunchSpec defaultLaunch(
            final AppLaunchTarget target) {
        return new AndroidLaunchSpec(Kind.DEFAULT, target, null, "");
    }

    static AndroidLaunchSpec intent(
            final AppLaunchTarget target, final String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) {
            throw new IllegalArgumentException("missing Android launch Intent");
        }
        return new AndroidLaunchSpec(
                Kind.INTENT, target, null, intentUri);
    }

    static AndroidLaunchSpec intent(
            final AppLaunchTarget target, final Intent intent) {
        return new AndroidLaunchSpec(Kind.INTENT, target, intent, "");
    }

    Intent resolve(final PackageManager packageManager) {
        if (kind == Kind.DEFAULT) {
            return target.resolve(packageManager);
        }
        final Intent intent;
        if (mIntent != null) {
            intent = new Intent(mIntent);
        } else {
            try {
                intent = Intent.parseUri(
                        mIntentUri, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException | RuntimeException error) {
                return null;
            }
        }
        if (intent.getComponent() == null && target != null) {
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
