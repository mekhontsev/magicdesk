package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;
import java.util.EnumSet;

final class RuntimeAccess {
    enum Backend {
        BASIC,
        SHIZUKU_SHELL,
        SHIZUKU_ROOT,
        ROOT
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
        PHONE_SCREEN_WAKE_GUARD,
        SCREENSHOT,
        CHARGE_SEPARATION,
        HARDWARE_CONTROL,
        KERNEL_FIXES
    }

    private static volatile SessionProfile sProfile =
            new SessionProfile(
                    SessionProfile.PrivilegeMode.AUTO,
                    SessionProfile.DisplayTarget.AUTO);
    private static volatile Backend sBackend = Backend.BASIC;

    private RuntimeAccess() {
    }

    static void initialize(final Context context) {
        sProfile = SessionProfile.load(context);
        sBackend = Backend.BASIC;
    }

    static void configure(
            final SessionProfile profile,
            final Backend backend) {
        sProfile = profile == null ? sProfile : profile;
        sBackend = backend == null ? Backend.BASIC : backend;
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

    static boolean allowsRootCommands() {
        return sBackend == Backend.ROOT;
    }

    static boolean allowsShizukuCommands() {
        return sBackend == Backend.SHIZUKU_SHELL
                || sBackend == Backend.SHIZUKU_ROOT;
    }

    static void requireRootCommands(final String operation) throws IOException {
        if (!allowsRootCommands()) {
            throw new IOException(
                    operation + " requires the Root runtime backend; active backend is "
                            + backendName());
        }
    }

    static String backendName() {
        switch (sBackend) {
            case ROOT:
                return "Root";
            case SHIZUKU_SHELL:
                return "Shizuku (shell)";
            case SHIZUKU_ROOT:
                return "Shizuku (root)";
            case BASIC:
            default:
                return "Basic";
        }
    }

    static EnumSet<Capability> capabilitiesFor(final Backend backend) {
        if (backend == Backend.ROOT) {
            return EnumSet.allOf(Capability.class);
        }
        if (backend == Backend.SHIZUKU_SHELL) {
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
                    Capability.SCREENSHOT,
                    Capability.CHARGE_SEPARATION);
        }
        if (backend == Backend.SHIZUKU_ROOT) {
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
                    Capability.SCREENSHOT,
                    Capability.CHARGE_SEPARATION);
        }
        return EnumSet.of(Capability.PUBLIC_APP_LAUNCH);
    }
}
