package io.github.mekhontsev.magicdesk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** A single bounded JSON request/response on an inherited local command channel. */
final class AutomationCommandWire {
    static final int REQUEST_LIMIT = 1024 * 1024;
    static final int RESPONSE_LIMIT = 32 * 1024 * 1024;

    private AutomationCommandWire() { }

    static JSONObject object(String text) throws JSONException {
        final JSONTokener tokens = new JSONTokener(text);
        final Object value = tokens.nextValue();
        if (!(value instanceof JSONObject object) || tokens.nextClean() != 0) {
            throw new JSONException("Expected one JSON object");
        }
        return object;
    }

    static JSONObject read(InputStream input, int limit) throws IOException, JSONException {
        final DataInputStream stream = new DataInputStream(input);
        final int length = stream.readInt();
        if (length < 2 || length > limit) throw new IOException("Invalid command message size");
        final byte[] bytes = new byte[length];
        stream.readFully(bytes);
        return object(new String(bytes, StandardCharsets.UTF_8));
    }

    static void write(OutputStream output, JSONObject object, int limit) throws IOException {
        final byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("Command result exceeds channel limit");
        final DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(bytes.length);
        stream.write(bytes);
        stream.flush();
    }
}
