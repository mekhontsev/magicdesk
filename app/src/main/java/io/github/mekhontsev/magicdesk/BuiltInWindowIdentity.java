package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Carries a hosted application's identity across the ordinary built-in launch boundary. */
final class BuiltInWindowIdentity {
    private static final String EXTRA = "magicdesk_window_application";

    static Intent bind(Intent intent, AppReference reference) {
        if (reference != null) intent.putExtra(EXTRA, reference.persistentKey());
        else intent.removeExtra(EXTRA);
        return intent;
    }

    static AppReference resolve(Context context, Intent intent, AppReference host) {
        if (!intent.hasExtra(EXTRA)) return host == null ? null : host.windowStateKey();
        AppReference reference = AppReference.fromPersistentKey(intent.getStringExtra(EXTRA));
        reference.application.requireProfile(AppProfile.current(context));
        if (host == null || !host.application.equals(reference.application) || host.builtIn != reference.builtIn
                || reference.hostedRecipe.isEmpty()) throw new IllegalArgumentException("Hosted application does not belong to this window");
        return reference;
    }

    private BuiltInWindowIdentity() { }
}
