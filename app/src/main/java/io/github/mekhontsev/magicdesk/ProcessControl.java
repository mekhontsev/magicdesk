package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.util.function.Consumer;

/** Signals one captured incarnation, using its existing authorized execution boundary. */
final class ProcessControl {
    static boolean allowed(Context context, SystemProcessSnapshot process) {
        final int profile = AppProfile.current(context).userId;
        return process.pid > 1 && process.startTicks > 0
                && process.uid != android.os.Process.myUid()
                && (process.uid == 2000 || process.uid / 100000 == profile && process.uid % 100000 >= 10000);
    }

    static void signal(Context context, SystemProcessSnapshot process, boolean force, Consumer<Throwable> callback) {
        if (!allowed(context, process)) { callback.accept(new SecurityException("Process is protected")); return; }
        final String command = ShellCommandLine.quote(context.getApplicationInfo().nativeLibraryDir
                + "/libmagicdesk_process_signal.so") + " " + process.pid + " " + process.uid + " "
                + process.startTicks + " " + (force ? 9 : 15);
        final var endpoint = TermuxIntegration.inspect(context);
        if (process.uid == endpoint.uid && endpoint.available()) {
            try {
                TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint, command,
                        "MagicDesk process signal", endpoint.homeDirectory, 5000, (result, error) ->
                        callback.accept(error != null ? error : result != null && result.success() ? null
                                : new IOException(result == null ? "Missing command result" : result.usefulMessage())));
            } catch (RuntimeException error) { callback.accept(error); }
        } else {
            TaskCommandQueue.execute(() -> {
                try {
                    final var result = ShellAccess.executeCommand(command);
                    callback.accept(result.exitCode == 0 ? null : new IOException(result.output));
                } catch (IOException | RuntimeException error) { callback.accept(error); }
            });
        }
    }
    private ProcessControl() { }
}
