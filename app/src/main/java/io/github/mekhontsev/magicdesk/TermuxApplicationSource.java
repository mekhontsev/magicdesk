package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;

/** Read-only, profile-local catalog through the selected Termux RUN_COMMAND endpoint. */
final class TermuxApplicationSource {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(Context context, TermuxIntegration.Endpoint endpoint,
            ApplicationCatalogSource.Completion<DesktopApplicationRepository.Entry> complete) {
        try {
            TermuxIntegration.runBackgroundShellCommandForResult(context.getApplicationContext(), endpoint,
                    TermuxApplicationCommand.create(), "MagicDesk application catalog", endpoint.homeDirectory, 15_000,
                    (result, failure) -> MAIN.post(() -> {
                        List<DesktopApplicationRepository.Entry> entries;
                        try {
                            if (failure != null) throw new IllegalStateException(ShellAccess.usefulMessage(failure));
                            if (result == null || !result.success()) throw new IllegalStateException(
                                    result == null ? "No Termux catalog result" : result.usefulMessage());
                            entries = TermuxApplicationRecords.parse(result.stdout);
                        } catch (RuntimeException invalid) {
                            complete.complete(List.of(), ShellAccess.usefulMessage(invalid));
                            return;
                        }
                        complete.complete(entries, "");
                    }));
        } catch (RuntimeException failure) { complete.complete(List.of(), ShellAccess.usefulMessage(failure)); }
    }
    private TermuxApplicationSource() { }
}
