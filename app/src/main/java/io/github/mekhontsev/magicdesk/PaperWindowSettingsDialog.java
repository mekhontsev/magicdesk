package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Reuses native settings dialogs and the shared Desktop preference store. */
final class PaperWindowSettingsDialog {
    private PaperWindowSettingsDialog() {}

    static void show(Activity activity) {
        final var settings = MagicDeskSettings.load();
        final String[] rows = {
            activity.getString(R.string.paper_input_first) + ": " + describe(activity, settings.paperInputFirst),
            activity.getString(R.string.paper_input_second) + ": " + describe(activity, settings.paperInputSecond)
        };
        UiDialogs.builder(activity).setTitle(R.string.paper_shortcuts).setItems(rows, (dialog, index) -> {
            choose(activity, index == 1);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String describe(Activity activity, PaperInputBinding binding) {
        if (binding.action() == PaperInputAction.SYSTEM) return activity.getString(R.string.paper_input_unassigned);
        int mask = binding.modifiers();
        return ((mask & 1) != 0 ? "Ctrl + " : "") + ((mask & 2) != 0 ? "Alt + " : "")
                + ((mask & 4) != 0 ? "Shift + " : "") + ((mask & 8) != 0 ? "Super + " : "")
                + KeyEvent.keyCodeToString(binding.key()).replace("KEYCODE_", "")
                + " — " + activity.getString(label(binding.action()));
    }

    private static void choose(Activity activity, boolean second) {
        final var settings = MagicDeskSettings.load();
        final var actions = PaperInputAction.values();
        final String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) labels[i] = activity.getString(label(actions[i]));
        UiDialogs.builder(activity).setTitle(second ? R.string.paper_input_second : R.string.paper_input_first)
                .setSingleChoiceItems(labels, (second ? settings.paperInputSecond : settings.paperInputFirst).action().ordinal(),
                        (dialog, index) -> {
                            dialog.dismiss();
                            if (actions[index] == PaperInputAction.SYSTEM) {
                                saved(activity, MagicDeskSettings.setPaperInputBinding(second, PaperInputBinding.NONE));
                                show(activity);
                            } else capture(activity, second, actions[index]);
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void capture(Activity activity, boolean second, PaperInputAction action) {
        final var dialog = UiDialogs.builder(activity).setTitle(R.string.paper_input_capture)
                .setMessage(R.string.paper_input_capture_help)
                .setNegativeButton(android.R.string.cancel, null).create();
        // Existing mappings must not consume the chord being recorded by this modal.
        DesktopShortcutService.setRecordingInput(true);
        dialog.setOnDismissListener(ignored -> DesktopShortcutService.setRecordingInput(false));
        final PaperInputBinding[] pending = {null};
        dialog.setOnKeyListener((owner, key, event) -> {
            final var device = event.getDevice();
            if (device == null || device.isVirtual() || !device.isExternal()) return false;
            if (!event.isFromSource(InputDevice.SOURCE_KEYBOARD)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    android.widget.Toast.makeText(activity, R.string.paper_input_capture_help,
                            android.widget.Toast.LENGTH_LONG).show();
                }
                return true;
            }
            if (KeyEvent.isModifierKey(key)) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pending[0] = new PaperInputBinding(key,
                        PaperInputBinding.modifiers(event.isCtrlPressed(), event.isAltPressed(),
                                event.isShiftPressed(), event.isMetaPressed()), action);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && pending[0] != null && pending[0].key() == key) {
                if (!event.isCanceled()) saved(activity, MagicDeskSettings.setPaperInputBinding(second, pending[0]));
                dialog.dismiss();
                show(activity);
            }
            return true;
        });
        dialog.show();
    }

    private static void saved(Activity activity, boolean success) {
        if (!success) android.widget.Toast.makeText(activity, R.string.paper_settings_failed,
                android.widget.Toast.LENGTH_LONG).show();
    }

    private static int label(PaperInputAction action) {
        return switch (action) {
            case SYSTEM -> R.string.paper_input_unassigned;
            case PREVIOUS -> R.string.paper_previous;
            case NEXT -> R.string.paper_next;
            case MOVE_LEFT -> R.string.paper_move_left;
            case MOVE_RIGHT -> R.string.paper_move_right;
            case WIDTH -> R.string.paper_width;
            case FLOAT -> R.string.paper_float;
        };
    }
}
