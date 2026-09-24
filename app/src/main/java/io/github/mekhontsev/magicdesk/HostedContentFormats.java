package io.github.mekhontsev.magicdesk;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Format negotiation and URI-list parsing, without Android or X server state. */
final class HostedContentFormats {
    static final String PNG = "image/png", HTML = "text/html", FILES = "text/uri-list";
    static final HostedContentFormats MIME = new HostedContentFormats(
            List.of("text/plain;charset=utf-8", "text/plain"), Map.of());
    private final List<String> textTypes;
    private final Map<String, Charset> legacyCharsets;

    HostedContentFormats(List<String> textTypes, Map<String, Charset> legacyCharsets) {
        this.textTypes = List.copyOf(textTypes);
        this.legacyCharsets = Map.copyOf(legacyCharsets);
    }

    Charset charset(String type) { return legacyCharsets.getOrDefault(type, StandardCharsets.UTF_8); }

    String textType(List<String> types) {
        for (String type : textTypes) if (types.contains(type)) return type;
        return null;
    }

    List<String> dragTypes(List<String> types) {
        List<String> result = new ArrayList<>();
        // A text/plain file is not an inline text selection.
        boolean files = types.contains(FILES);
        if (!files && types.contains("text/plain")) result.addAll(publishedTextTypes());
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

    private List<String> publishedTextTypes() {
        return textTypes.stream().filter(type -> !legacyCharsets.containsKey(type)).toList();
    }

    List<String> formats(AndroidContentPayload payload) {
        List<String> result = new ArrayList<>();
        if (!payload.text.isEmpty()) result.addAll(publishedTextTypes());
        if (!payload.htmlText.isEmpty()) result.add(HTML);
        if (!payload.uriItems.isEmpty()) result.add(FILES);
        if (payload.uriItems.size() == 1 && PNG.equals(payload.uriItems.get(0).mimeType)) result.add(PNG);
        return List.copyOf(result);
    }
}
