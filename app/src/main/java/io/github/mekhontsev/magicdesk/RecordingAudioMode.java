package io.github.mekhontsev.magicdesk;

enum RecordingAudioMode {
    AUTO("auto"),
    MICROPHONE("microphone"),
    NONE("none");

    private final String mStoredValue;

    RecordingAudioMode(final String storedValue) {
        mStoredValue = storedValue;
    }

    String storedValue() {
        return mStoredValue;
    }

    static RecordingAudioMode fromStoredValue(final String value) {
        for (final RecordingAudioMode mode : values()) {
            if (mode.mStoredValue.equals(value)) {
                return mode;
            }
        }
        return AUTO;
    }
}
