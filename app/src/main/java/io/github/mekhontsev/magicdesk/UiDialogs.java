package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;

/** Native modals share the owner's appearance and inset policy, not its lifecycle callbacks. */
final class UiDialogs {
    private static final java.util.Set<AlertDialog> DIALOGS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private UiDialogs() {}

    static void dismissOwned(Activity owner) {
        // Dismiss callbacks may create or close other dialogs.
        for (AlertDialog dialog : java.util.List.copyOf(DIALOGS)) {
            if (dialog.getOwnerActivity() == owner && dialog.isShowing()) dialog.dismiss();
        }
    }

    static AlertDialog.Builder builder(Activity owner) {
        return new Builder(owner);
    }

    private static final class Builder extends AlertDialog.Builder {
        private final Activity owner;

        Builder(Activity owner) {
            super(owner);
            this.owner = owner;
        }

        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOwnerActivity(owner);
            DIALOGS.add(dialog);
            dialog.create();
            UiAppearance.dialog(dialog);
            DialogContentInsets.bind(dialog.findViewById(android.R.id.content), owner);
            return dialog;
        }
    }
}
