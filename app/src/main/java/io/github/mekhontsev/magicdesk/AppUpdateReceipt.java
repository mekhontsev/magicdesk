package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** One bounded, checksummed worker result; partial writes never become installer success. */
final class AppUpdateReceipt {
    static final int MAX_BYTES = 8192;

    private AppUpdateReceipt() { }

    static void merge(JSONObject receipt, JSONObject result) throws IOException, JSONException {
        if (!receipt.getString("updateId").equals(result.getString("updateId"))
                || receipt.getInt("sessionId") != result.getInt("sessionId")) {
            throw new IOException("worker receipt belongs to another update");
        }
        final String state = result.getString("state");
        if (!java.util.Set.of("installed", "failed", "user_action_required", "completion_unknown").contains(state)) {
            throw new IOException("invalid worker result state");
        }
        receipt.put("state", state).put("detail", result.optString("detail"));
        if (result.has("installerStatus")) receipt.put("installerStatus", result.getInt("installerStatus"));
    }

    static void write(OutputStream stream, JSONObject result) throws IOException {
        final byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("update receipt exceeds size limit");
        final DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.write(digest(bytes));
        out.flush();
    }

    static JSONObject read(InputStream stream) throws IOException, JSONException {
        final DataInputStream in = new DataInputStream(stream);
        try {
            final int length = in.readInt();
            if (length <= 0 || length > MAX_BYTES) throw new IOException("invalid update receipt size");
            final byte[] bytes = new byte[length];
            in.readFully(bytes);
            final byte[] expected = new byte[32];
            in.readFully(expected);
            if (!MessageDigest.isEqual(expected, digest(bytes))) {
                throw new IOException("invalid update receipt digest");
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (EOFException pending) {
            return null;
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
