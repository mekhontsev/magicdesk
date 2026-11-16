package io.github.mekhontsev.magicdesk.guestapp;

import java.util.List;

final class EnvironmentCommand {
    static String terminal(String runtime, String store, List<String> connections) {
        // mksh is the PTY child. Its parent is the relay, not the Android service.
        StringBuilder command = new StringBuilder("exec ").append(quote(runtime))
                .append(" --parent-pid \"$PPID\" login ").append(quote(store));
        for (String argument : connections) command.append(' ').append(quote(argument));
        return command.toString();
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private EnvironmentCommand() { }
}
