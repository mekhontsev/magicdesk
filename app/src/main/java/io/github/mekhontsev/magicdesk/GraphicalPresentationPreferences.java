package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Profile-private Linux preferences, scoped to the captured executor and launch source. */
final class GraphicalPresentationPreferences {
    static String key(String executor, String desktopFile) {
        return desktopFile == null || desktopFile.isBlank() ? "" : executor + "|" + desktopFile;
    }

    static int load(Context context, String key) {
        int value = key.isEmpty() ? 100 : context.getSharedPreferences("graphics_presentation", Context.MODE_PRIVATE).getInt(key, 100);
        return AppPresentationProfile.isValidScale(value) ? value : 100;
    }

    static void save(Context context, String key, int scale) {
        if (!AppPresentationProfile.isValidScale(scale)) throw new IllegalArgumentException("Invalid Linux scale");
        if (key.isEmpty()) return;
        var edit = context.getSharedPreferences("graphics_presentation", Context.MODE_PRIVATE).edit();
        if (scale == 100) edit.remove(key); else edit.putInt(key, scale);
        edit.apply();
        for (var session : GraphicalSessions.list())
            if (!session.stopped() && session.presentationKey().equals(key)) session.setScale(scale);
    }

    static void save(Context context, GraphicalSessions.Session session, int scale) {
        if (session.stopped()) throw new IllegalArgumentException("Graphical session is unavailable");
        session.setScale(scale);
        save(context, session.presentationKey(), scale);
    }

    private GraphicalPresentationPreferences() { }
}
