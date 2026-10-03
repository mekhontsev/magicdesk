package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/** Shared HTTP transport; authentication and file publication belong to the caller. */
final class HttpGet {
    interface Connections { HttpURLConnection open(URI uri) throws IOException; }
    static final Connections SYSTEM = uri -> (HttpURLConnection) uri.toURL().openConnection();
    static final int MAX_REQUESTS = 8;

    private HttpGet() { }

    static void requireHttp(URI uri) throws IOException {
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IOException("Use an HTTP(S) URL without userinfo or fragments");
        }
    }

    static HttpURLConnection open(Connections connections, URI uri, Map<String, String> headers)
            throws IOException {
        requireHttp(uri);
        final HttpURLConnection connection = connections.open(uri);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            // EVENT_WAIT: socket connect/read; timeout fails the transfer, never implies completion.
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            headers.forEach(connection::setRequestProperty);
            connection.getResponseCode();
            return connection;
        } catch (IOException | RuntimeException error) {
            connection.disconnect();
            throw error;
        }
    }

    static URI redirect(URI current, HttpURLConnection connection) throws IOException {
        final int status = connection.getResponseCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
        final String location = connection.getHeaderField("Location");
        if (location == null || location.isBlank()) throw new IOException("HTTP redirect has no Location");
        final URI next;
        try { next = current.resolve(location); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid HTTP redirect", error); }
        requireHttp(next);
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
            throw new IOException("HTTP redirect cannot downgrade HTTPS");
        }
        return next;
    }

    static HttpURLConnection get(Connections connections, URI original) throws IOException {
        URI uri = original;
        for (int i = 0; i < MAX_REQUESTS; ++i) {
            final HttpURLConnection connection = open(connections, uri, Map.of("User-Agent", "MagicDesk/1"));
            boolean retained = false;
            try {
                final int status = connection.getResponseCode();
                if (status == 200 || status == 204) {
                    retained = true;
                    return connection;
                }
                final URI next = redirect(uri, connection);
                if (next == null) throw new IOException("HTTP " + status + " at " + uri.getHost());
                uri = next;
            } finally { if (!retained) connection.disconnect(); }
        }
        throw new IOException("HTTP redirect limit exceeded");
    }
}
