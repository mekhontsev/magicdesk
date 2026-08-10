package io.github.mekhontsev.magicdesk;

enum OnScreenKeyboardLocation {
    PHONE("phone"),
    DESKTOP("desktop");

    final String storedValue;

    OnScreenKeyboardLocation(final String storedValue) {
        this.storedValue = storedValue;
    }

    static OnScreenKeyboardLocation fromStoredValue(final String value) {
        return DESKTOP.storedValue.equals(value) ? DESKTOP : PHONE;
    }
}
