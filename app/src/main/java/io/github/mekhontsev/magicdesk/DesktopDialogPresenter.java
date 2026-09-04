package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;

/** Presents a dialog in the application-window layer owned by its UI surface. */
interface DesktopDialogPresenter {
    @FunctionalInterface
    interface Factory {
        AlertDialog create(Activity host);
    }

    boolean show(Factory factory);
}
