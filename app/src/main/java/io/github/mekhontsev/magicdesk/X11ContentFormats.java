package io.github.mekhontsev.magicdesk;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Format negotiation and URI-list parsing, without Android or X server state. */
final class X11ContentFormats {
    static final String PNG = "image/png", HTML = "text/html", FILES = "text/uri-list";
    static final List<String> TEXT = List.of("UTF8_STRING", "text/plain;charset=utf-8", "text/plain", "STRING");

    static String textType(List<String> types) {
        for (String type : TEXT) if (types.contains(type)) return type;
        return null;
    }

    static List<String> dragTypes(List<String> types) {
        List<String> result = new ArrayList<>();
        // A text/plain file is not an inline text selection.
        boolean files = types.contains(FILES);
        if (!files && types.contains("text/plain")) result.addAll(TEXT.subList(0, 3));
        if (!files && types.contains(HTML)) result.add(HTML);
        if (types.contains(PNG)) result.add(PNG);
        if (types.stream().anyMatch(type -> !type.equals("text/plain") && !type.equals(HTML))) result.add(FILES);
        return List.copyOf(result);
    }

    static List<String> files(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            URI uri = URI.create(line);
            if (!"file".equals(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null ||
                    (uri.getAuthority() != null && !uri.getAuthority().isEmpty() && !"localhost".equals(uri.getAuthority())) ||
                    uri.getPath() == null || !uri.getPath().startsWith("/") || uri.getPath().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Only local file URIs can be transferred");
            if (!result.contains(line)) result.add(line);
            if (result.size() > 64) throw new IllegalArgumentException("Too many transferred files");
        }
        return List.copyOf(result);
    }

    static String fileName(String value) {
        String path = URI.create(value).getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() || name.equals(".") || name.equals("..") ? "content" : name;
    }

    private X11ContentFormats() { }
}
