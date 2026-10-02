package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** A retained drawable may outlive its host; its theme source must not keep that host alive. */
final class AppearanceScopeSource {
    private final java.lang.ref.WeakReference<Context> mContext;
    AppearanceScopeSource(Context context) { mContext = new java.lang.ref.WeakReference<>(context); }
    ShellAppearance current() { return resolve().theme(); }
    AppearanceStore.ResolvedAppearance resolve() { return AppearanceStore.resolved(mContext.get()); }
}
