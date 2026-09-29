package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Installs the same Android-only CLI entry point in each explicitly launched shell environment. */
final class CommandShellEnvironment {
    static final String APK_ENV = "MAGICDESK_COMMAND_APK";
    private static volatile Values sValues;
    static final String SCRIPT = "#!/system/bin/sh\n"
            + "unset LD_PRELOAD LD_LIBRARY_PATH\n"
            + "export CLASSPATH=\"${" + APK_ENV + ":?Open a new MagicDesk console}\"\n"
            + "exec /system/bin/app_process / io.github.mekhontsev.magicdesk.MagicDeskCli \"$@\"\n";

    private CommandShellEnvironment() { }

    static void configure(String endpoint, String apk, String libraries) throws IOException {
        if (endpoint == null || !endpoint.contains(":") || apk == null || !apk.startsWith("/")) {
            throw new IllegalArgumentException("Invalid command environment");
        }
        final Path directory = Path.of(ShellExecutionEnvironment.prepareToolsDirectory());
        install(directory.resolve("magicdesk"), SCRIPT);
        install(directory.resolve(GuestLaunchPlan.TOOL), GuestRuntimeCommand.SCRIPT);
        sValues = new Values(endpoint, apk, libraries);
    }

    private static void install(Path script, String contents) throws IOException {
        final Path directory = script.getParent();
        final Path temporary = Files.createTempFile(directory, ".magicdesk-", ".tmp");
        try {
            Files.write(temporary, contents.getBytes(StandardCharsets.UTF_8));
            android.system.Os.chmod(temporary.toString(), 0700);
            Files.move(temporary, script, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (android.system.ErrnoException error) {
            throw new IOException("Cannot install CLI entry point", error);
        } finally { Files.deleteIfExists(temporary); }
    }

    static void apply(Map<String, String> environment) {
        final Values values = sValues;
        // Never retain a channel inherited from an unrelated launcher or an older process.
        environment.remove(MagicDeskCli.ENDPOINT_ENV);
        environment.remove(APK_ENV);
        environment.remove(GuestRuntimeCommand.LIBRARIES_ENV);
        if (values == null) return;
        environment.put(MagicDeskCli.ENDPOINT_ENV, values.endpoint);
        environment.put(APK_ENV, values.apk);
        environment.put(GuestRuntimeCommand.LIBRARIES_ENV, values.libraries);
    }

    static String termuxSetup(String endpoint, String apk) {
        // The script carries no authority: only this invocation inherits the private channel.
        return "export " + MagicDeskCli.ENDPOINT_ENV + "=" + ShellCommandLine.quote(endpoint) + "\n"
                + "export " + APK_ENV + "=" + ShellCommandLine.quote(apk) + "\n"
                + "md_bin=\"${HOME:?}/.local/libexec/magicdesk\"\n"
                + "mkdir -p \"$md_bin\"\n"
                + "md_script=" + ShellCommandLine.quote(SCRIPT) + "\n"
                + "if [ ! -f \"$md_bin/magicdesk\" ] || [ \"$(cat \"$md_bin/magicdesk\")\" != \"${md_script%?}\" ]; then\n"
                + "  md_tmp=\"$md_bin/.magicdesk.$$\"\n"
                + "  printf '%s' \"$md_script\" > \"$md_tmp\" && chmod 700 \"$md_tmp\" && mv -f \"$md_tmp\" \"$md_bin/magicdesk\" || exit 1\n"
                + "fi\n"
                + "export PATH=\"$md_bin:$PATH\"\n";
    }

    private record Values(String endpoint, String apk, String libraries) { }
}
