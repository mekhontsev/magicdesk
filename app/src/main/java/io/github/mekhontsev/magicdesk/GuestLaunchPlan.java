package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Argument-only contract shared by terminal, background and graphical launch adapters. */
public record GuestLaunchPlan(GuestEnvironment environment, String directory, List<String> command, boolean magicDesk) {
    public static final String TOOL = "magicdesk-guest";
    public GuestLaunchPlan(GuestEnvironment environment, String directory, List<String> command) {
        this(environment, directory, command, false);
    }

    public GuestLaunchPlan {
        Objects.requireNonNull(environment, "environment");
        directory = environment.image() && (directory == null || directory.isEmpty()) ? ""
                : GuestEnvironment.absolute(directory, "guest directory");
        command = List.copyOf(command);
        if (command.isEmpty() && !environment.image() || command.size() > 900 || !command.isEmpty() && !command.get(0).startsWith("/"))
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
        var result = new ArrayList<>(environment.image()
                ? List.of(TOOL, "image", command.isEmpty() ? "login" : "exec", environment.store())
                : List.of(TOOL, "--store", environment.store(), "--home", environment.home()));
        if (!directory.isEmpty()) result.addAll(List.of("--cwd", directory));
        if (environment.image() && !environment.home().isEmpty()) result.addAll(List.of("--env", "HOME=" + environment.home()));
        if (!environment.user().isEmpty()) result.addAll(List.of("--user", environment.user()));
        if (magicDesk) result.add("--magicdesk");
        return List.copyOf(result);
    }

    public static void requireIdentity(int uid) {
        if (uid != 2000 && uid != 0) throw new IllegalStateException("Shroot requires the selected shell or root executor");
    }
}
