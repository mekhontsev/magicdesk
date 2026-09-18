package io.github.mekhontsev.magicdesk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded decoding and XDG desktop-file identity, independent of the command transport. */
final class TermuxApplicationRecords {
    static List<DesktopApplicationRepository.Entry> parse(String output) {
        if (output.length() > 512 * 1024 || !output.endsWith("END\n"))
            throw new IllegalArgumentException("Termux application catalog is incomplete or too large");
        List<DesktopApplicationRepository.Entry> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String body = output.substring(0, output.length() - 4);
        for (String line : body.split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || ids.size() >= 256) throw new IllegalArgumentException("Invalid Termux catalog record");
            String path = decode(fields[0]);
            int directory = path.indexOf("/applications/");
            if (!path.startsWith("/") || directory < 0 || path.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid application path");
            String id = path.substring(directory + "/applications/".length()).replace('/', '-');
            if (!ids.add(id)) continue;
            var entry = DesktopEntryFile.parseTermuxCatalogEntry(decode(fields[1]), path);
            if (entry != null) result.add(entry);
        }
        result.sort(Comparator.comparing(entry -> entry.shortcut.name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    private TermuxApplicationRecords() { }
}
