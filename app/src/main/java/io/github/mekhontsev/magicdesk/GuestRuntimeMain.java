package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;

/** Short-lived shell-side preparation. The calling shell retains all guest descriptors. */
public final class GuestRuntimeMain {
    public static void main(String[] arguments) {
        try {
            GuestLaunchPlan.requireIdentity(android.system.Os.getuid());
            if (arguments.length != 2)
                throw new IllegalArgumentException("Expected native-library and runtime-store directories");
            System.out.println(GuestRuntimeArtifacts.prepare(Path.of(arguments[0]), Path.of(arguments[1])));
        } catch (Exception error) {
            System.err.println("Guest runtime preparation failed: " + error.getMessage());
            System.exit(1);
        }
    }
    private GuestRuntimeMain() { }
}
