package io.github.mekhontsev.magicdesk;

/** Independently selectable part of the firmware integration. */
public enum PlatformComponent {
    WINDOWING("windowing"),
    POINTER("pointer"),
    PROJECTION("projection"),
    PHONE_UI("phoneUi"),
    WALLPAPER("wallpaper"),
    DIAGNOSTICS("diagnostics"),
    AUDIO_CAPTURE("audioCapture"),
    TEXT_INPUT("textInput"),
    INPUT_ROUTING("inputRouting"),
    SYSTEM_CONTROLS("systemControls"),
    LAUNCH_TARGETS("launchTargets"),
    RUNTIME("runtime");

    public final String wireName;

    PlatformComponent(final String wireName) {
        this.wireName = wireName;
    }
}
