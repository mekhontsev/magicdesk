package io.github.mekhontsev.magicdesk;

import java.util.Locale;

final class FileSizeFormatter {
    private FileSizeFormatter() {
    }

    static String format(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
