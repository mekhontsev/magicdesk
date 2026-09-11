package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Transport choice, independent of the working service's privilege policy. */
enum ShellBackend {
    SHIZUKU("Shizuku"), ROOT("Root (su)");

    final String label;
    ShellBackend(String label) { this.label = label; }
    boolean usesRoot() { return this == ROOT; }
    static ShellBackend active() { return Active.VALUE; }
    static ShellBackend configured(Context context) {
        return valueOf(context.getSharedPreferences("privileged_service", Context.MODE_PRIVATE)
                .getString("backend", SHIZUKU.name()));
    }
    boolean save(Context context) {
        return context.getSharedPreferences("privileged_service", Context.MODE_PRIVATE)
                .edit().putString("backend", name()).commit();
    }
    private static final class Active {
        static final ShellBackend VALUE = configured(MagicDeskApplication.applicationContext());
    }
}
