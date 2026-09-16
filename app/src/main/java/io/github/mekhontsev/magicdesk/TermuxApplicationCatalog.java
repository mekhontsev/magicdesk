package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only, profile-local catalog through the selected Termux RUN_COMMAND endpoint. */
final class TermuxApplicationCatalog {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static List<DesktopApplicationRepository.Entry> entries = List.of();
    private static String owner = "";
    private static boolean loading;
    private static String lastError = "";
    private static final List<Consumer<String>> waiting = new ArrayList<>();

    static List<DesktopApplicationRepository.Entry> entries() { return entries; }

    static List<DesktopApplicationRepository.Entry> load(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Termux catalog query cannot block the UI");
        CountDownLatch ready = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<List<DesktopApplicationRepository.Entry>>();
        var failure = new java.util.concurrent.atomic.AtomicReference<String>();
        MAIN.post(() -> {
            if (!TermuxIntegration.isAvailable(context)) {
                failure.set("Termux RUN_COMMAND is unavailable");
                ready.countDown();
                return;
            }
            refresh(context, error -> {
                result.set(List.copyOf(entries));
                failure.set(error);
                ready.countDown();
            });
        });
        try {
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.APPLICATION_CATALOG);
            if (!ready.await(16_000, TimeUnit.MILLISECONDS)) throw new IOException("Termux catalog query timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Termux catalog query was interrupted", error);
        }
        if (failure.get() != null && !failure.get().isEmpty()) throw new IOException(failure.get());
        return result.get();
    }

    static void refresh(Context context, Consumer<String> complete) {
        TermuxIntegration.Endpoint endpoint = TermuxIntegration.inspect(context);
        if (!endpoint.available()) { entries = List.of(); complete.accept(""); return; }
        String identity = endpoint.service.flattenToString() + "|" + endpoint.uid + "|" + endpoint.homeDirectory;
        if (!identity.equals(owner)) { owner = identity; entries = List.of(); }
        waiting.add(complete);
        if (loading) return;
        loading = true;
        // NUL-safe paths and base64 records; the terminal marker detects truncated plugin output.
        String script = "set -e\ncount=0; total=0\n"
                + "for directory in \"${XDG_DATA_HOME:-$HOME/.local/share}/applications\" \"$PREFIX/share/applications\"; do\n"
                + " [ -d \"$directory\" ] || continue\n"
                + " while IFS= read -r -d '' file; do\n"
                + "  size=$(wc -c < \"$file\"); [ \"$size\" -le 65536 ] || continue\n"
                + "  entry=$(LC_ALL=C awk '/^\\[/{section=($0==\"[Desktop Entry]\");next} section && /^(Type|Name|Icon|Exec|Path|Terminal|Hidden|NoDisplay|MimeType|X-MagicDesk-X11Mode)=/{print}' \"$file\")\n"
                + "  size=${#entry}; count=$((count+1)); total=$((total+size)); [ \"$count\" -le 256 ] && [ \"$total\" -le 65536 ] || exit 1\n"
                + "  printf '%s' \"$file\" | base64 -w 0; printf '\\t'; printf '[Desktop Entry]\\n%s\\n' \"$entry\" | base64 -w 0; printf '\\n'\n"
                + " done < <(find \"$directory\" -type f -name '*.desktop' -print0)\n"
                + "done\nprintf 'END\\n'\n";
        try {
            TermuxIntegration.runBackgroundShellCommandForResult(context.getApplicationContext(), endpoint,
                    script, "MagicDesk application catalog", endpoint.homeDirectory, 15_000,
                    (result, failure) -> MAIN.post(() -> {
                        String error = "";
                        try {
                            if (failure != null) throw new IllegalStateException(ShellAccess.usefulMessage(failure));
                            if (result == null || !result.success()) throw new IllegalStateException(
                                    result == null ? "No Termux catalog result" : result.usefulMessage());
                            if (identity.equals(owner)) entries = TermuxApplicationRecords.parse(result.stdout);
                        } catch (RuntimeException invalid) { error = ShellAccess.usefulMessage(invalid); }
                        finish(error);
                    }));
        } catch (RuntimeException failure) { finish(ShellAccess.usefulMessage(failure)); }
    }

    private static void finish(String error) {
        if (!error.isEmpty() && !error.equals(lastError))
            CompatibilityDiagnostics.record("X11-CATALOG-001", "Could not read Termux applications", error);
        lastError = error;
        loading = false;
        List<Consumer<String>> callbacks = List.copyOf(waiting);
        waiting.clear();
        for (Consumer<String> callback : callbacks) callback.accept(error);
    }
    private TermuxApplicationCatalog() { }
}
