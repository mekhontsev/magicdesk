package io.github.mekhontsev.magicdesk;

final class ShortcutCatalog {
    static final Entry[] ENTRIES = {
        new Entry(R.string.shortcut_maximize,
                R.string.shortcut_maximize_action),
        new Entry(R.string.shortcut_restore,
                R.string.shortcut_restore_action),
        new Entry(R.string.shortcut_snap_left,
                R.string.shortcut_snap_left_action),
        new Entry(R.string.shortcut_snap_right,
                R.string.shortcut_snap_right_action),
        new Entry(R.string.shortcut_close,
                R.string.shortcut_close_action),
        new Entry(R.string.shortcut_back,
                R.string.shortcut_back_action),
        new Entry(R.string.shortcut_lock,
                R.string.shortcut_lock_action),
        new Entry(R.string.shortcut_notifications,
                R.string.shortcut_notifications_action),
        new Entry(R.string.shortcut_system,
                R.string.shortcut_system_action),
        new Entry(R.string.shortcut_settings,
                R.string.shortcut_settings_action),
        new Entry(R.string.shortcut_screenshot,
                R.string.shortcut_screenshot_action),
        new Entry(R.string.shortcut_recording,
                R.string.shortcut_recording_action),
        new Entry(R.string.shortcut_desktop,
                R.string.shortcut_desktop_action),
        new Entry(R.string.shortcut_help,
                R.string.shortcut_help_action),
        new Entry(R.string.shortcut_layout,
                R.string.shortcut_layout_action),
        new Entry(R.string.shortcut_previous,
                R.string.shortcut_previous_action),
        new Entry(R.string.shortcut_next,
                R.string.shortcut_next_action)
    };

    private ShortcutCatalog() {
    }

    static final class Entry {
        final int keysResId;
        final int actionResId;

        Entry(final int keysResId, final int actionResId) {
            this.keysResId = keysResId;
            this.actionResId = actionResId;
        }
    }
}
