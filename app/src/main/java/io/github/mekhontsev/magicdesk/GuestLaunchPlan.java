package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Argument-only contract shared by terminal, background and graphical launch adapters. */
public record GuestLaunchPlan(GuestEnvironment environment, String directory, List<String> command) {
    public static final String TOOL = "magicdesk-guest";

    public GuestLaunchPlan {
        Objects.requireNonNull(environment, "environment");
        directory = GuestEnvironment.absolute(directory, "guest directory");
        command = List.copyOf(command);
        if (command.isEmpty() || command.size() > 900 || !command.get(0).startsWith("/"))
            throw new IllegalArgumentException("Guest command must name an absolute executable");
        int size = 0;
        for (String argument : command) {
            if (argument.indexOf('\0') >= 0 || (size += argument.length()) > 65536)
                throw new IllegalArgumentException("Invalid guest command arguments");
        }
    }

    public List<String> arguments() {
        var result = new ArrayList<>(launcherArguments());
        result.add("--");
        result.addAll(command);
        return List.copyOf(result);
    }

    List<String> launcherArguments() {
        var result = new ArrayList<>(List.of(TOOL, "--store", environment.store(), "--home", environment.home(), "--cwd", directory));
        if (!environment.user().isEmpty()) result.addAll(List.of("--user", environment.user()));
        return List.copyOf(result);
    }

    public static void requireIdentity(int uid) {
        if (uid != 2000 && uid != 0) throw new IllegalStateException("Guest runtime requires the selected shell or root executor");
    }
}
