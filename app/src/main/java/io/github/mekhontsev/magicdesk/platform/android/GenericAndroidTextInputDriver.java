package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;

/** Standard Android has no API for editing a focused projected window. */
final class GenericAndroidTextInputDriver implements PlatformTextInputDriver {
    private static final RuntimeState UNAVAILABLE = new RuntimeState(
            "unsupported", "not provided by the selected platform");

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Session capture() {
        return null;
    }

    @Override
    public void verifyApi() throws ReflectiveOperationException {
        throw new NoSuchMethodException(
                "projected-window text input is unavailable");
    }

    @Override
    public RuntimeState runtimeState() {
        return UNAVAILABLE;
    }
}
