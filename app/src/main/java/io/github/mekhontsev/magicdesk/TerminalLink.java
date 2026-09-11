package io.github.mekhontsev.magicdesk;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** OSC text is untrusted. Only explicit user actions can open these destinations. */
record TerminalLink(String uri, String localPath, boolean canOpen) {
    static TerminalLink parse(final String value) {
        try {
            final URI uri = new URI(value);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null
                    && uri.getUserInfo() == null) { return new TerminalLink(value, null, true); }
            final String host = uri.getHost();
            if (scheme.equals("file") && (uri.getRawAuthority() == null || "localhost".equalsIgnoreCase(host))
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && uri.getPath() != null && uri.getPath().startsWith("/")
                    && uri.getPath().codePoints().noneMatch(Character::isISOControl)) {
                return new TerminalLink(value, uri.getPath(), true);
            }
        } catch (URISyntaxException ignored) { }
        return new TerminalLink(value, null, false);
    }
}
