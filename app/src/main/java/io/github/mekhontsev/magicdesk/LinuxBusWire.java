package io.github.mekhontsev.magicdesk;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded transport for libdbus values, not a second implementation of the D-Bus wire format. */
final class LinuxBusWire {
    static final int LIMIT = 1024 * 1024, VALUES = 16384, DEPTH = 32;
    record Message(int id, int kind, int bus, int bytes, JSONObject data) { }
    private int values;

    static JSONObject value(String type, Object value) throws JSONException {
        return new JSONObject().put("type", type).put("value", value);
    }

    static byte[] request(int id, int op, int bus, int timeout, JSONObject args) throws IOException, JSONException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(id); out.writeInt(op); out.writeInt(bus); out.writeInt(timeout);
        if (op == 1) {
            for (String key : new String[]{"destination", "path", "interface", "member"}) text(out, args.getString(key));
            JSONArray values = args.optJSONArray("arguments");
            new LinuxBusWire().writeValues(out, values == null ? new JSONArray() : values, 0);
        } else if (op == 2 || op == 3) text(out, args.getString("rule"));
        else if (op == 5) text(out, args.getString("fd"));
        else if (op == 6) for (String key : new String[]{"argv", "environment"}) {
            JSONArray list = args.getJSONArray(key); out.writeInt(list.length());
            for (int i = 0; i < list.length(); ++i) text(out, list.getString(i));
        }
        if (bytes.size() > LIMIT) throw new IOException("D-Bus request exceeds frame limit");
        return bytes.toByteArray();
    }

    static Message read(DataInputStream stream) throws IOException, JSONException {
        return read(stream, () -> { });
    }

    static Message read(DataInputStream stream, Runnable frameStarted) throws IOException, JSONException {
        int first = stream.readUnsignedByte();
        frameStarted.run();
        int size = (first << 24) | (stream.readUnsignedByte() << 16) | stream.readUnsignedShort();
        if (size < 40 || size > LIMIT) throw new IOException("Invalid D-Bus frame length");
        byte[] frame = new byte[size]; stream.readFully(frame);
        var in = new DataInputStream(new ByteArrayInputStream(frame));
        int id = in.readInt(), kind = in.readInt(), bus = in.readInt();
        if (kind < 0 || kind > 3 || bus < 0 || bus > 2) throw new IOException("Invalid D-Bus frame kind");
        JSONObject data = new JSONObject();
        for (String key : new String[]{"sender", "path", "interface", "member", "errorName", "errorDetail"}) data.put(key, text(in));
        data.put("values", new LinuxBusWire().readValues(in, 0));
        if (in.available() != 0) throw new IOException("Trailing D-Bus frame data");
        return new Message(id, kind, bus, size, data);
    }

    private void writeValues(DataOutputStream out, JSONArray list, int depth) throws IOException, JSONException {
        if (depth > DEPTH || list.length() > VALUES - values) throw new IOException("D-Bus value limit");
        out.writeInt(list.length());
        for (int i = 0; i < list.length(); ++i) {
            ++values;
            JSONObject item = list.getJSONObject(i);
            String type = item.getString("type");
            if (type.isEmpty() || type.length() > 255) throw new IOException("Invalid D-Bus type");
            text(out, type);
            if (type.equals("v")) {
                JSONObject child = item.getJSONObject("value");
                text(out, child.getString("type"));
                writeValues(out, new JSONArray().put(child), depth + 1);
            } else if (container(type)) writeValues(out, item.getJSONArray("value"), depth + 1);
            else {
                Object scalar = item.get("value");
                if (!(scalar instanceof String || scalar instanceof Boolean || scalar instanceof Number))
                    throw new IOException("Invalid D-Bus scalar");
                text(out, scalar.toString());
            }
            if (out.size() > LIMIT) throw new IOException("D-Bus frame limit");
        }
    }

    private JSONArray readValues(DataInputStream in, int depth) throws IOException, JSONException {
        int count = in.readInt();
        if (depth > DEPTH || count < 0 || count > VALUES - values) throw new IOException("D-Bus value limit");
        JSONArray list = new JSONArray();
        for (int i = 0; i < count; ++i) {
            ++values;
            String type = text(in);
            if (type.isEmpty() || type.length() > 255) throw new IOException("Invalid D-Bus type");
            Object value;
            if (type.equals("v")) {
                String contained = text(in);
                JSONArray children = readValues(in, depth + 1);
                if (children.length() != 1 || !contained.equals(children.getJSONObject(0).getString("type")))
                    throw new IOException("Invalid D-Bus variant");
                value = children.getJSONObject(0);
            } else value = container(type) ? readValues(in, depth + 1) : text(in);
            list.put(value(type, value));
        }
        return list;
    }

    private static boolean container(String type) { return "a({".indexOf(type.charAt(0)) >= 0; }
    private static void text(DataOutputStream out, String value) throws IOException {
        if (value.length() > LIMIT || value.indexOf('\0') >= 0) throw new IOException("Invalid D-Bus string");
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
        if (buffer.remaining() > LIMIT) throw new IOException("D-Bus string limit");
        out.writeInt(buffer.remaining());
        out.write(buffer.array(), buffer.position(), buffer.remaining());
    }
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("Invalid D-Bus string length");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (value.indexOf('\0') >= 0) throw new IOException("Invalid D-Bus string");
        return value;
    }
}
