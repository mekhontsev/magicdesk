package io.github.mekhontsev.magicdesk;

/** Stable capability identifiers used by diagnostics and compatibility data. */
public enum PlatformCapabilityId {
    DESKTOP_PHONE("desktop.phone", PlatformComponent.RUNTIME),
    DESKTOP_SIMULATED("desktop.simulated", PlatformComponent.RUNTIME),
    DESKTOP_WIRED("desktop.wired", PlatformComponent.PROJECTION),
    DESKTOP_WIRELESS("desktop.wireless", PlatformComponent.PROJECTION),
    OUTPUT_CONFIGURATION(
            "projection.outputConfiguration", PlatformComponent.PROJECTION),
    EXTERNAL_INPUT_BRIDGE(
            "input.externalBridge", PlatformComponent.EXTERNAL_INPUT),
    POINTER_OBSERVATION("input.pointerObservation", PlatformComponent.POINTER),
    PHONE_UI("phone.ui", PlatformComponent.PHONE_UI),
    INTERNAL_AUDIO_CAPTURE(
            "capture.internalAudio", PlatformComponent.AUDIO_CAPTURE),
    VENDOR_HARDWARE("hardware.vendorControls", PlatformComponent.SYSTEM_CONTROLS);

    public final String wireName;
    public final PlatformComponent component;

    PlatformCapabilityId(
            final String wireName,
            final PlatformComponent component) {
        this.wireName = wireName;
        this.component = component;
    }
}
