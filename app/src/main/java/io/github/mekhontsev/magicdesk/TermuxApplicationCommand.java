package io.github.mekhontsev.magicdesk;

/** Read-only catalog command executed in the selected Termux environment. */
final class TermuxApplicationCommand {
    static String create() {
        // Follow file links, not linked subdirectories; keep launcher paths for XDG overrides.
        // NUL-safe paths and base64 records; END detects truncated plugin output.
        return "set -e\ncount=0; total=0\n"
                + "user_directory=\"${XDG_DATA_HOME:-$HOME/.local/share}/applications\"\n"
                + "case \"$(realpath -m -- \"$user_directory\")/\" in \"$(realpath -m -- \"$PREFIX\")/\"*) user_directory=;; esac\n"
                + "for directory in \"${XDG_DATA_HOME:-$HOME/.local/share}/applications\" \"$PREFIX/share/applications\"; do\n"
                + " [ -d \"$directory\" ] || continue\n"
                + " while IFS= read -r -d '' file; do\n"
                + "  [ -f \"$file\" ] && [ -r \"$file\" ] || continue\n"
                + "  size=$(wc -c < \"$file\"); [ \"$size\" -le 65536 ] || continue\n"
                + "  entry=$(LC_ALL=C awk '/^\\[/{section=($0==\"[Desktop Entry]\");next} section && /^(Type|Name|Icon|Exec|Path|Terminal|Hidden|NoDisplay|MimeType|X-MagicDesk-X11Mode)=/{print}' \"$file\")\n"
                + "  size=${#entry}; count=$((count+1)); total=$((total+size)); [ \"$count\" -le 256 ] && [ \"$total\" -le 65536 ] || exit 1\n"
                + "  printf '%s' \"$file\" | base64 -w 0; printf '\\t'\n"
                + "  { printf '[Desktop Entry]\\n%s\\n' \"$entry\"\n"
                + "    if [ \"${file%/*}\" = \"$user_directory\" ] && [ ! -L \"$file\" ]; then\n"
                + "      case \"${file##*/}\" in magicdesk-*.desktop) printf 'X-MagicDesk-UserShortcut=true\\n';; esac\n"
                + "    fi\n"
                + "  } | base64 -w 0; printf '\\n'\n"
                + " done < <(find -H \"$directory\" \\( -type f -o -type l \\) -name '*.desktop' -print0)\n"
                + "done\nprintf 'END\\n'\n";
    }

    private TermuxApplicationCommand() { }
}
