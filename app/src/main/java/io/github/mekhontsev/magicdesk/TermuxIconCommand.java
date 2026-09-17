package io.github.mekhontsev.magicdesk;

import java.util.Base64;
import java.util.List;

/** Bounded PNG lookup through Termux, without a theme engine or recursive filesystem scan. */
final class TermuxIconCommand {
    static final int BATCH_SIZE = 4;
    static final int MAX_BYTES = 16 * 1024;

    static boolean valid(String icon) {
        return icon != null && !icon.isEmpty() && icon.length() <= 1024
                && icon.indexOf('\0') < 0 && icon.indexOf('\n') < 0 && icon.indexOf('\r') < 0
                && (icon.startsWith("/") || icon.indexOf('/') < 0);
    }

    static String create(List<String> icons) {
        if (icons.isEmpty() || icons.size() > BATCH_SIZE || !icons.stream().allMatch(TermuxIconCommand::valid))
            throw new IllegalArgumentException("Invalid icon request");
        return """
                exec 2>/dev/null
                emit_icon() {
                  [ -f "$1" ] && [ -r "$1" ] || return 1
                  local size
                  size=$(stat -Lc %%s -- "$1") || return 1
                  [ "$size" -gt 0 ] && [ "$size" -le %d ] || return 1
                  head -c %d -- "$1" | base64 -w 0
                }
                lookup_icon() {
                  local name="$1" root size file
                  case "$name" in
                    /*) emit_icon "$name"; return $? ;;
                    *.png) name=${name%%.png} ;;
                  esac
                  for root in "$HOME/.icons" "${XDG_DATA_HOME:-$HOME/.local/share}/icons" "$PREFIX/share/icons"; do
                    for size in 96 64 48 128 32 256 192 24 16 512; do
                      file="$root/hicolor/${size}x${size}/apps/$name.png"
                      emit_icon "$file" && return 0
                    done
                    emit_icon "$root/$name.png" && return 0
                  done
                  for root in "${XDG_DATA_HOME:-$HOME/.local/share}/pixmaps" "$PREFIX/share/pixmaps"; do
                    emit_icon "$root/$name.png" && return 0
                  done
                  return 1
                }
                for icon in %s; do
                  lookup_icon "$icon"
                  printf '\\n'
                done
                printf 'END\\n'
                """.formatted(MAX_BYTES, MAX_BYTES,
                        icons.stream().map(ShellCommandLine::quote).collect(java.util.stream.Collectors.joining(" ")));
    }

    static List<byte[]> parse(String output, int count) {
        // Four base64-encoded 16 KiB images stay below Termux's 100 KiB result limit.
        if (count < 1 || count > BATCH_SIZE || output == null || output.length() > 90 * 1024)
            throw new IllegalArgumentException("Invalid icon result size");
        final String[] lines = output.split("\n", -1);
        if (lines.length != count + 2 || !lines[count].equals("END") || !lines[count + 1].isEmpty())
            throw new IllegalArgumentException("Incomplete icon result");
        final var result = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < count; i++) {
            final byte[] bytes = Base64.getDecoder().decode(lines[i]);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Icon is too large");
            result.add(bytes);
        }
        return List.copyOf(result);
    }

    private TermuxIconCommand() { }
}
