package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

final class MagicDeskSettings {
    private MagicDeskSettings() {
    }

    static Values load() {
        return DesktopStateStore.read(
                state -> state.settings.copy(), Values.defaults());
    }

    static boolean setTaskbarAutoHide(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.taskbarAutoHide = enabled);
    }

    static boolean setKeepDesktopAwake(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.keepDesktopAwake = enabled);
    }

    static boolean setOpenTouchpadAutomatically(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.openTouchpadAutomatically = enabled);
    }

    static boolean setOpenFilesWithSingleClick(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.openFilesWithSingleClick = enabled);
    }

    static final class Values {
        private static final String TASKBAR_AUTO_HIDE = "taskbarAutoHide";
        private static final String KEEP_DESKTOP_AWAKE = "keepDesktopAwake";
        private static final String OPEN_TOUCHPAD_AUTOMATICALLY =
                "openTouchpadAutomatically";
        private static final String OPEN_FILES_WITH_SINGLE_CLICK =
                "openFilesWithSingleClick";

        boolean taskbarAutoHide;
        boolean keepDesktopAwake;
        boolean openTouchpadAutomatically;
        boolean openFilesWithSingleClick;

        static Values defaults() {
            final Values values = new Values();
            values.openTouchpadAutomatically = true;
            return values;
        }

        static Values fromJson(final JSONObject json) {
            final Values values = defaults();
            if (json != null) {
                values.taskbarAutoHide = json.optBoolean(
                        TASKBAR_AUTO_HIDE, false);
                values.keepDesktopAwake = json.optBoolean(
                        KEEP_DESKTOP_AWAKE, false);
                values.openTouchpadAutomatically = json.optBoolean(
                        OPEN_TOUCHPAD_AUTOMATICALLY, true);
                values.openFilesWithSingleClick = json.optBoolean(
                        OPEN_FILES_WITH_SINGLE_CLICK, false);
            }
            return values;
        }

        Values copy() {
            final Values copy = new Values();
            copy.taskbarAutoHide = taskbarAutoHide;
            copy.keepDesktopAwake = keepDesktopAwake;
            copy.openTouchpadAutomatically = openTouchpadAutomatically;
            copy.openFilesWithSingleClick = openFilesWithSingleClick;
            return copy;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject json = new JSONObject();
            json.put(TASKBAR_AUTO_HIDE, taskbarAutoHide);
            json.put(KEEP_DESKTOP_AWAKE, keepDesktopAwake);
            json.put(
                    OPEN_TOUCHPAD_AUTOMATICALLY,
                    openTouchpadAutomatically);
            json.put(
                    OPEN_FILES_WITH_SINGLE_CLICK,
                    openFilesWithSingleClick);
            return json;
        }
    }
}
