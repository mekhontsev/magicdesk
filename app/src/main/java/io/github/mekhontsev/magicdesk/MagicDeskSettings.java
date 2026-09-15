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

    static boolean setDisableAdaptiveBrightnessOnExternalDesktop(
            final boolean enabled) {
        return DesktopStateStore.update(state ->
                state.settings.disableAdaptiveBrightnessOnExternalDesktop =
                        enabled);
    }

    static boolean setOpenTouchpadAutomatically(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.openTouchpadAutomatically = enabled);
    }

    static boolean setKeyboardOnAppDisplay(final boolean enabled) {
        return DesktopStateStore.update(state -> state.settings.keyboardOnAppDisplay = enabled);
    }

    static boolean setCompatibilityOption(
            final DesktopCompatibilityPolicy.Option option, final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.compatibility.put(option, enabled));
    }

    static boolean resetCompatibilityOptions() {
        return DesktopStateStore.update(state -> state.settings.resetCompatibilityOptions());
    }

    static boolean setOpenFilesWithSingleClick(final boolean enabled) {
        return DesktopStateStore.update(
                state -> state.settings.openFilesWithSingleClick = enabled);
    }

    static final class Values {
        private static final String TASKBAR_AUTO_HIDE = "taskbarAutoHide";
        private static final String KEEP_DESKTOP_AWAKE = "keepDesktopAwake";
        private static final String DISABLE_ADAPTIVE_BRIGHTNESS =
                "disableAdaptiveBrightnessOnExternalDesktop";
        private static final String OPEN_TOUCHPAD_AUTOMATICALLY =
                "openTouchpadAutomatically";
        private static final String COMPATIBILITY = "compatibility";
        private static final String KEYBOARD_ON_APP_DISPLAY = "keyboardOnAppDisplay";
        private static final String OPEN_FILES_WITH_SINGLE_CLICK =
                "openFilesWithSingleClick";

        boolean taskbarAutoHide;
        boolean keepDesktopAwake;
        boolean disableAdaptiveBrightnessOnExternalDesktop;
        boolean openTouchpadAutomatically;
        boolean keyboardOnAppDisplay;
        // Unset follows the platform recommendation without persisting it.
        final java.util.EnumMap<DesktopCompatibilityPolicy.Option, Boolean> compatibility =
                new java.util.EnumMap<>(DesktopCompatibilityPolicy.Option.class);
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
                values.disableAdaptiveBrightnessOnExternalDesktop =
                        json.optBoolean(DISABLE_ADAPTIVE_BRIGHTNESS, false);
                values.openTouchpadAutomatically = json.optBoolean(
                        OPEN_TOUCHPAD_AUTOMATICALLY, true);
                values.keyboardOnAppDisplay = json.optBoolean(KEYBOARD_ON_APP_DISPLAY, false);
                final JSONObject options = json.optJSONObject(COMPATIBILITY);
                if (options != null) {
                    for (final DesktopCompatibilityPolicy.Option option
                            : DesktopCompatibilityPolicy.Option.values()) {
                        if (options.opt(option.key) instanceof Boolean) {
                            values.compatibility.put(option, options.optBoolean(option.key));
                        }
                    }
                }
                values.openFilesWithSingleClick = json.optBoolean(
                        OPEN_FILES_WITH_SINGLE_CLICK, false);
            }
            return values;
        }

        Values copy() {
            final Values copy = new Values();
            copy.taskbarAutoHide = taskbarAutoHide;
            copy.keepDesktopAwake = keepDesktopAwake;
            copy.disableAdaptiveBrightnessOnExternalDesktop =
                    disableAdaptiveBrightnessOnExternalDesktop;
            copy.openTouchpadAutomatically = openTouchpadAutomatically;
            copy.keyboardOnAppDisplay = keyboardOnAppDisplay;
            copy.compatibility.putAll(compatibility);
            copy.openFilesWithSingleClick = openFilesWithSingleClick;
            return copy;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject json = new JSONObject();
            json.put(TASKBAR_AUTO_HIDE, taskbarAutoHide);
            json.put(KEYBOARD_ON_APP_DISPLAY, keyboardOnAppDisplay);
            json.put(KEEP_DESKTOP_AWAKE, keepDesktopAwake);
            json.put(
                    DISABLE_ADAPTIVE_BRIGHTNESS,
                    disableAdaptiveBrightnessOnExternalDesktop);
            json.put(
                    OPEN_TOUCHPAD_AUTOMATICALLY,
                    openTouchpadAutomatically);
            final JSONObject options = new JSONObject();
            for (final DesktopCompatibilityPolicy.Option option : compatibility.keySet()) {
                options.put(option.key, compatibility.get(option));
            }
            json.put(COMPATIBILITY, options);
            json.put(
                    OPEN_FILES_WITH_SINGLE_CLICK,
                    openFilesWithSingleClick);
            return json;
        }

        void resetCompatibilityOptions() {
            compatibility.clear();
        }

        DesktopCompatibilityPolicy compatibilityPolicy(
                final PlatformFeatures features) {
            DesktopCompatibilityPolicy result = features.compatibilityDefaults;
            for (final DesktopCompatibilityPolicy.Option option : compatibility.keySet()) {
                result = result.with(option, compatibility.get(option));
            }
            return result;
        }
    }
}
