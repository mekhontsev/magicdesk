package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Anonymous HTTPS OCI pulls into a verified blob cache and a private manifest index. */
final class GuestOciRegistry {
    record Pulled(Path layout, String digest) { }
    private static final String ACCEPT = "application/vnd.oci.image.index.v1+json, "
            + "application/vnd.oci.image.manifest.v1+json, "
            + "application/vnd.docker.distribution.manifest.list.v2+json, "
            + "application/vnd.docker.distribution.manifest.v2+json";
    private static final Pattern AUTH_PARAMETER = Pattern.compile("\\s*([A-Za-z_]+)=\"((?:[^\"\\\\]|\\\\.)*)\"\\s*(?:,|$)");
    private final HttpGet.Connections connections;
    private final Consumer<String> progress;
    private String token;
    private GuestOciReference reference;

    GuestOciRegistry(Consumer<String> progress) {
        this(HttpGet.SYSTEM, progress);
    }

    GuestOciRegistry(HttpGet.Connections connections, Consumer<String> progress) {
        this.connections = connections; this.progress = progress;
    }

    Pulled pull(String source, Path cache, Path layout) throws Exception {
        reference = GuestOciReference.parse(source); token = null;
        Files.createDirectories(cache);
        Files.createDirectories(layout);
        String id = reference.reference();
        JSONObject expected = id.startsWith("sha256:") ? new JSONObject().put("digest", id) : null;
        byte[] bytes = null;
        JSONObject manifest = null;
        for (int depth = 0; depth < 5; ++depth) {
            bytes = jsonBytes(reference.endpoint("manifests", id), true);
            verify(bytes, expected);
            manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (manifest.optInt("schemaVersion") != 2) throw new IOException("Unsupported OCI schema");
            if (!manifest.has("manifests")) break;
            JSONArray candidates = manifest.getJSONArray("manifests");
            expected = null;
            for (int i = 0; i < candidates.length(); ++i) {
                JSONObject candidate = candidates.getJSONObject(i);
                JSONObject platform = candidate.optJSONObject("platform");
                if (platform != null && "linux".equals(platform.optString("os"))
                        && "arm64".equals(platform.optString("architecture"))
                        && (platform.optString("variant").isEmpty() || "v8".equals(platform.optString("variant")))) {
                    if (expected != null) throw new IOException("Ambiguous ARM64 image index");
                    expected = candidate;
                }
            }
            if (expected == null) throw new IOException("Image has no Linux ARM64 variant");
            id = expected.getString("digest"); GuestImageFiles.hex(id);
        }
        if (manifest == null || manifest.has("manifests")) throw new IOException("OCI index nesting limit");
        String digest = "sha256:" + HexFormat.of().formatHex(GuestImageFiles.sha256().digest(bytes));
        Path manifestFile = cache.resolve(GuestImageFiles.hex(digest));
        if (Files.exists(manifestFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!GuestImageFiles.digest(manifestFile).equals(GuestImageFiles.hex(digest))) throw new IOException("Corrupt cached manifest");
        } else GuestImageFiles.write(manifestFile, bytes);
        fetch(manifest.getJSONObject("config"), cache, GuestImageFiles.JSON_LIMIT);
        JSONObject config = new JSONObject(GuestImageFiles.read(cache.resolve(GuestImageFiles.hex(
                manifest.getJSONObject("config").getString("digest")))));
        if (!"linux".equals(config.optString("os")) || !"arm64".equals(config.optString("architecture")))
            throw new IOException("Image configuration is not Linux ARM64");
        JSONArray layers = manifest.getJSONArray("layers");
        if (layers.length() > 256) throw new IOException("Too many image layers");
        long size = 0;
        for (int i = 0; i < layers.length(); ++i) {
            JSONObject layer = layers.getJSONObject(i);
            long length = layer.getLong("size");
            if (length < 0 || length > GuestImageFiles.BLOB_LIMIT - size) throw new IOException("Image exceeds download limit");
            size += length;
            fetch(layer, cache, GuestImageFiles.BLOB_LIMIT);
        }
        JSONObject descriptor = new JSONObject().put("mediaType", manifest.getString("mediaType"))
                .put("digest", digest).put("size", bytes.length);
        GuestImageFiles.write(layout.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");
        GuestImageFiles.write(layout.resolve("index.json"), new JSONObject().put("schemaVersion", 2)
                .put("manifests", new JSONArray().put(descriptor)).toString());
        return new Pulled(layout, digest);
    }

    private void fetch(JSONObject descriptor, Path cache, long limit) throws Exception {
        String digest = descriptor.getString("digest"), hex = GuestImageFiles.hex(digest);
        long size = descriptor.getLong("size");
        if (size < 0 || size > limit) throw new IOException("Invalid OCI blob size");
        Path cached = cache.resolve(hex);
        if (Files.exists(cached, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(cached) != size || !GuestImageFiles.digest(cached).equals(hex))
                throw new IOException("Corrupt cached blob " + digest);
        } else {
            progress.accept("Downloading " + digest + " (" + size + " bytes)");
            Path temporary = Files.createTempFile(cache, ".download-", ".part");
            HttpURLConnection connection = null;
            try {
                connection = request(reference.endpoint("blobs", digest), true);
                MessageDigest hash = GuestImageFiles.sha256();
                long copied;
                try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                    copied = GuestImageFiles.copy(input, output, size, hash);
                }
                if (copied != size || !hex.equals(HexFormat.of().formatHex(hash.digest())))
                    throw new IOException("OCI blob size or digest mismatch: " + digest);
                try (var file = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { file.force(true); }
                Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE);
                GuestImageFiles.syncDirectory(cache);
            } finally {
                if (connection != null) connection.disconnect();
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void verify(byte[] bytes, JSONObject expected) throws Exception {
        if (expected == null) return;
        String digest = GuestImageFiles.hex(expected.getString("digest"));
        if ((expected.has("size") && expected.getLong("size") != bytes.length)
                || !digest.equals(HexFormat.of().formatHex(GuestImageFiles.sha256().digest(bytes))))
            throw new IOException("OCI manifest size or digest mismatch");
    }

    private byte[] jsonBytes(URI uri, boolean authenticate) throws Exception {
        HttpURLConnection connection = request(uri, authenticate);
        try (InputStream input = connection.getInputStream(); var bytes = new java.io.ByteArrayOutputStream()) {
            GuestImageFiles.copy(input, bytes, GuestImageFiles.JSON_LIMIT, null);
            return bytes.toByteArray();
        } finally { connection.disconnect(); }
    }

    private HttpURLConnection request(URI original, boolean authenticate) throws Exception {
        URI uri = original;
        boolean challenged = false;
        for (int redirects = 0; redirects < HttpGet.MAX_REQUESTS; ++redirects) {
            requireHttps(uri);
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", ACCEPT);
            headers.put("User-Agent", "MagicDesk-Guest/1");
            boolean registry = authenticate && sameOrigin(uri, reference.endpoint("manifests", reference.reference()));
            if (registry && token != null) headers.put("Authorization", "Bearer " + token);
            HttpURLConnection connection = HttpGet.open(connections, uri, headers);
            boolean retained = false;
            final String challenge;
            try {
                int status = connection.getResponseCode();
                if (status == 200) { retained = true; return connection; }
                if (status == 401 && registry && !challenged) {
                    challenge = connection.getHeaderField("WWW-Authenticate");
                } else {
                    URI next = HttpGet.redirect(uri, connection);
                    if (next == null) throw new IOException("Registry HTTP " + status + " at " + uri.getHost()
                            + (status == 401 || status == 403 ? "; only anonymous pulls are supported" : ""));
                    uri = next;
                    continue;
                }
            } finally {
                if (!retained) connection.disconnect();
            }
            challenged = true;
            token = authorize(challenge);
        }
        throw new IOException("Registry redirect/authentication limit");
    }

    private String authorize(String challenge) throws Exception {
        Map<String, String> fields = bearer(challenge);
        URI realm = URI.create(fields.getOrDefault("realm", ""));
        requireHttps(realm);
        String query = "service=" + encode(fields.getOrDefault("service", ""))
                + "&scope=" + encode("repository:" + reference.repository() + ":pull");
        URI uri = URI.create(realm + (realm.getRawQuery() == null ? "?" : "&") + query);
        JSONObject response = new JSONObject(new String(jsonBytes(uri, false), StandardCharsets.UTF_8));
        String token = response.optString("token", response.optString("access_token"));
        if (token.isEmpty() || token.length() > 65536 || !token.matches("[A-Za-z0-9._~+/=-]+"))
            throw new IOException("Registry returned an invalid bearer token");
        return token;
    }

    static Map<String, String> bearer(String challenge) throws IOException {
        if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, 7))
            throw new IOException("Registry requires unsupported authentication");
        Map<String, String> result = new HashMap<>();
        var matcher = AUTH_PARAMETER.matcher(challenge);
        int position = 7;
        while (position < challenge.length()) {
            matcher.region(position, challenge.length());
            if (!matcher.lookingAt()) throw new IOException("Invalid registry challenge");
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = matcher.group(2).replaceAll("\\\\(.)", "$1");
            if (result.put(key, value) != null) throw new IOException("Duplicate registry challenge parameter");
            position = matcher.end();
        }
        return result;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort());
    }
    private static void requireHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IOException("Registry and token endpoints must use HTTPS without userinfo or fragments");
    }
}
