package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;

/** Short-lived shell-side preparation. The calling shell retains all guest descriptors. */
public final class GuestRuntimeMain {
    public static void main(String[] arguments) {
        try {
            int uid = android.system.Os.getuid();
            GuestLaunchPlan.requireIdentity(uid);
            if (arguments.length != 2)
                throw new IllegalArgumentException("Expected native-library and runtime-store directories");
            // Modern app UIDs cannot execute copies from writable app data.
            // Packaged helpers already share one immutable APK lifetime.
            Path runtime = uid == 0 || uid == 2000
                    ? GuestRuntimeArtifacts.prepare(Path.of(arguments[0]), Path.of(arguments[1]))
                    : GuestRuntimeArtifacts.packaged(Path.of(arguments[0]));
            Path commands = uid == 0 || uid == 2000 ? runtime.resolve("commands")
                    : GuestRuntimeArtifacts.commands(Path.of(arguments[0]), Path.of(arguments[1]));
            System.out.println("md_guest=" + ShellCommandLine.quote(runtime.toString()));
            System.out.println("md_commands=" + ShellCommandLine.quote(commands.toString()));
        } catch (Exception error) {
            System.err.println("Shroot preparation failed: " + error.getMessage());
            System.exit(1);
        }
    }
    private GuestRuntimeMain() { }
}
