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
