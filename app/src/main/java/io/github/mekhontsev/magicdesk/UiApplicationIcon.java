package io.github.mekhontsev.magicdesk;

import android.widget.ImageView;

/** Own symbols follow the host palette; application artwork keeps its original colors. */
final class UiApplicationIcon {
    static void bind(ImageView view, AppItem app, UiColor color) {
        int symbol = BuiltInDesktopAppCatalog.symbolicIcon(app.launchTarget);
        if (symbol != 0) UiAppearance.icon(view, symbol, color);
        else view.setImageDrawable(app.icon);
    }
}
