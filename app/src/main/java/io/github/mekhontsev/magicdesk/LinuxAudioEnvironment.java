package io.github.mekhontsev.magicdesk;

/** Audio launch addresses are independent of the selected graphics protocol and guest launcher. */
final class LinuxAudioEnvironment {
    static final String GUEST_NATIVE = LinuxConnectionContract.PIPEWIRE_PATH;
    static final String GUEST_PULSE = LinuxConnectionContract.PULSE_PATH;

    // In a shell function: prepend explicit guest routes, without leaking Android filesystem addresses.
    static String shrootArguments() {
        return "if [ -n \"${MAGICDESK_AUDIO_NATIVE_ENDPOINT:-}\" ] && [ -n \"${MAGICDESK_AUDIO_PULSE_ENDPOINT:-}\" ]; then\n"
                + "set -- --socket-path " + GUEST_NATIVE + " \"$MAGICDESK_AUDIO_NATIVE_ENDPOINT\""
                + " --socket-path " + GUEST_PULSE + " \"$MAGICDESK_AUDIO_PULSE_ENDPOINT\""
                + " --env PIPEWIRE_REMOTE=" + GUEST_NATIVE + " --env PULSE_SERVER=unix:" + GUEST_PULSE
                + " --env MAGICDESK_AUDIO_PIPEWIRE=" + GUEST_NATIVE + " --env MAGICDESK_AUDIO_PULSE=" + GUEST_PULSE
                + " \"$@\"\nfi\n";
    }

    static String prootArguments() {
        return " --bind \"${MAGICDESK_AUDIO_PIPEWIRE%/*}:/tmp/magicdesk-audio-native\""
                + " --bind \"${MAGICDESK_AUDIO_PULSE%/*}:/tmp/magicdesk-audio-pulse\""
                + " --env PIPEWIRE_REMOTE=/tmp/magicdesk-audio-native/socket"
                + " --env PULSE_SERVER=unix:/tmp/magicdesk-audio-pulse/socket"
                + " --env MAGICDESK_AUDIO_PIPEWIRE=/tmp/magicdesk-audio-native/socket"
                + " --env MAGICDESK_AUDIO_PULSE=/tmp/magicdesk-audio-pulse/socket";
    }

    private LinuxAudioEnvironment() { }
}
