package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Private to this Android profile and selected Termux package; needs no shell state store. */
final class X11PresentationPreferences {
    static String key(String termuxPackage, String desktopFile) {
        return desktopFile == null || desktopFile.isBlank() ? "" : termuxPackage + "|" + desktopFile;
    }

    static int load(Context context, String key) {
        int value = key.isEmpty() ? 100 : context.getSharedPreferences("x11_presentation", Context.MODE_PRIVATE).getInt(key, 100);
        return AppPresentationProfile.isValidScale(value) ? value : 100;
    }

    static void save(Context context, String key, int scale) {
        if (!AppPresentationProfile.isValidScale(scale)) throw new IllegalArgumentException("Invalid X11 scale");
        if (key.isEmpty()) return;
        var edit = context.getSharedPreferences("x11_presentation", Context.MODE_PRIVATE).edit();
        if (scale == 100) edit.remove(key); else edit.putInt(key, scale);
        edit.apply();
        for (var session : X11Sessions.list()) if (session.presentationKey.equals(key)) session.setScale(scale);
    }

    private X11PresentationPreferences() { }
}
