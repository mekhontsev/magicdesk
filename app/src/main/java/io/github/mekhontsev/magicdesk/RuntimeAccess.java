package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.EnumSet;

final class RuntimeAccess {
    enum Backend {
        UNAVAILABLE,
        SHIZUKU
    }

    enum Capability {
        PUBLIC_APP_LAUNCH,
        EXACT_TASKS,
        TASK_CONTROL,
        GLOBAL_INPUT,
        KEYBOARD_LAYOUT_CONTROL,
        KEYBOARD_LAYOUT_SHORTCUT,
        RIGHT_CLICK_REMAP,
        CONSOLE_CONTROL,
        DISPLAY_OVERRIDES,
        EXTERNAL_CAPTION_VISIBILITY,
        PHONE_SCREEN_CONTROL,
        DEVICE_LOCK,
        SCREENSHOT,
        SYSTEM_WALLPAPER_READ,
        CHARGE_SEPARATION,
        HARDWARE_MONITORING,
        HARDWARE_VENDOR_CONTROL
    }

    private static volatile SessionProfile sProfile =
            new SessionProfile(SessionProfile.DisplayTarget.AUTO);
    private static volatile Backend sBackend = Backend.UNAVAILABLE;

    private RuntimeAccess() {
    }

    static void initialize(final Context context) {
        sProfile = SessionProfile.load(context);
        sBackend = Backend.UNAVAILABLE;
    }

    static void configure(
            final SessionProfile profile,
            final Backend backend) {
        sProfile = profile == null ? sProfile : profile;
        sBackend = backend == null ? Backend.UNAVAILABLE : backend;
    }

    static SessionProfile profile() {
        return sProfile;
    }

    static Backend backend() {
        return sBackend;
    }

    static boolean has(final Capability capability) {
        return capabilitiesFor(sBackend).contains(capability);
    }

    static boolean allowsShizukuCommands() {
        return sBackend == Backend.SHIZUKU;
    }

    static String backendName() {
        switch (sBackend) {
            case SHIZUKU:
                return "Shizuku (shell)";
            case UNAVAILABLE:
            default:
                return "Unavailable";
        }
    }

    static EnumSet<Capability> capabilitiesFor(final Backend backend) {
        if (backend == Backend.SHIZUKU) {
            return EnumSet.of(
                    Capability.PUBLIC_APP_LAUNCH,
                    Capability.EXACT_TASKS,
                    Capability.TASK_CONTROL,
                    Capability.GLOBAL_INPUT,
                    Capability.KEYBOARD_LAYOUT_CONTROL,
                    Capability.KEYBOARD_LAYOUT_SHORTCUT,
                    Capability.RIGHT_CLICK_REMAP,
                    Capability.CONSOLE_CONTROL,
                    Capability.DISPLAY_OVERRIDES,
                    Capability.EXTERNAL_CAPTION_VISIBILITY,
                    Capability.PHONE_SCREEN_CONTROL,
                    Capability.DEVICE_LOCK,
                    Capability.SCREENSHOT,
                    Capability.SYSTEM_WALLPAPER_READ,
                    Capability.CHARGE_SEPARATION,
                    Capability.HARDWARE_MONITORING,
                    Capability.HARDWARE_VENDOR_CONTROL);
        }
        return EnumSet.noneOf(Capability.class);
    }
}
