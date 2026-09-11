package io.github.mekhontsev.magicdesk;

import java.net.Socket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** APK entry point. It loads neither Application nor Desktop in the calling process. */
public final class MagicDeskCli {
    static final String ENDPOINT_ENV = "MAGICDESK_COMMAND_ENDPOINT";

    private MagicDeskCli() { }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err, MagicDeskCli::execute));
    }

    interface Executor { JSONObject execute(String name, JSONObject args) throws Exception; }

    static int run(String[] argv, InputStream input, PrintStream out, PrintStream err, Executor executor) {
        try {
            if (argv.length == 0 || argv[0].equals("--help")) {
                out.print(help(null));
                return 0;
            }
            final String name = argv[0];
            final JSONObject command = AutomationCommandArguments.command(name);
            if (argv.length == 2 && argv[1].equals("--help")) {
                out.print(help(command));
                return 0;
            }
            if (argv.length == 2 && argv[1].equals("--schema")) {
                out.println(command.toString(2));
                return 0;
            }
            final JSONObject schema = command.getJSONObject("inputSchema").getJSONObject("properties");
            JSONObject args = new JSONObject();
            boolean dryRun = false;
            boolean wholeObject = false;
            for (int i = 1; i < argv.length; i++) {
                String option = argv[i];
                if (option.equals("--dry-run")) { dryRun = true; continue; }
                if (option.equals("--args")) {
                    if (wholeObject || args.length() != 0 || ++i == argv.length) {
                        throw new IllegalArgumentException("Use --args JSON|@file|- without named arguments");
                    }
                    args = AutomationCommandWire.object(readArguments(argv[i], input));
                    wholeObject = true;
                    continue;
                }
                if (wholeObject || !option.startsWith("--")) {
                    throw new IllegalArgumentException("Expected a named argument, got: " + option);
                }
                final int equals = option.indexOf('=');
                final String key = option.substring(2, equals < 0 ? option.length() : equals);
                final JSONObject property = schema.optJSONObject(key);
                if (property == null) throw new IllegalArgumentException("Unknown argument: " + key);
                if (args.has(key)) throw new IllegalArgumentException("Repeated argument: " + key);
                final String type = property.getString("type");
                String value;
                if (equals >= 0) value = option.substring(equals + 1);
                else if (type.equals("boolean") && (i + 1 == argv.length || argv[i + 1].startsWith("--"))) value = "true";
                else if (++i < argv.length) value = argv[i];
                else throw new IllegalArgumentException("Missing value for " + key);
                args.put(key, parse(type, value));
            }
            AutomationCommandArguments.check(name, args);
            if (dryRun) {
                out.println(new JSONObject().put("name", name).put("arguments", args));
                return 0;
            }
            try {
                final JSONObject result = executor.execute(name, args);
                final int status = result.getBoolean("success") ? 0 : 1;
                out.println(result);
                return status;
            } catch (Exception error) {
                final String message = error instanceof java.io.EOFException
                        ? "Command channel closed without a result; the action outcome is unknown."
                        : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                err.println("magicdesk: " + message);
                return 3;
            }
        } catch (IllegalArgumentException | JSONException | IOException error) {
            err.println("magicdesk: " + error.getMessage());
            return 2;
        }
    }

    private static Object parse(String type, String text) throws JSONException {
        if (type.equals("string")) return text;
        final JSONTokener tokens = new JSONTokener(text);
        final Object value = tokens.nextValue();
        if (tokens.nextClean() != 0 || !AutomationCommandArguments.matches(type, value)) {
            throw new IllegalArgumentException("Expected " + type + ", got: " + text);
        }
        return value;
    }

    private static String readArguments(String value, InputStream input) throws IOException {
        if (value.equals("-")) return readText(input);
        if (value.startsWith("@")) {
            try (InputStream file = Files.newInputStream(Path.of(value.substring(1)))) { return readText(file); }
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > AutomationCommandWire.REQUEST_LIMIT) {
            throw new IOException("Arguments exceed size limit");
        }
        return value;
    }

    private static String readText(InputStream input) throws IOException {
        final byte[] bytes = input.readNBytes(AutomationCommandWire.REQUEST_LIMIT + 1);
        if (bytes.length > AutomationCommandWire.REQUEST_LIMIT) throw new IOException("Arguments exceed size limit");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String help(JSONObject command) throws JSONException {
        final StringBuilder out = new StringBuilder();
        if (command == null) {
            out.append("Usage: magicdesk COMMAND [--argument VALUE] [--args JSON|@file|-]\n")
                    .append("       magicdesk COMMAND --help|--schema\n\n");
            final JSONArray catalog = AutomationCommandCatalog.create();
            final List<String> rows = new ArrayList<>();
            for (int i = 0; i < catalog.length(); i++) {
                final JSONObject item = catalog.getJSONObject(i);
                rows.add(item.getString("name") + "  " + item.getString("title"));
            }
            Collections.sort(rows);
            for (String row : rows) out.append(row).append('\n');
            return out.toString();
        }
        out.append("Usage: magicdesk ").append(command.getString("name"))
                .append(" [--argument VALUE]\n\n").append(command.getString("description")).append("\n\n");
        final JSONObject schema = command.getJSONObject("inputSchema");
        final JSONObject properties = schema.getJSONObject("properties");
        final List<String> names = new ArrayList<>();
        properties.keys().forEachRemaining(names::add);
        Collections.sort(names);
        for (String name : names) {
            final JSONObject property = properties.getJSONObject(name);
            out.append("  --").append(name).append(" <").append(property.getString("type")).append(">  ")
                    .append(property.optString("description")).append('\n');
            if (property.has("enum")) out.append("    Choices: ").append(property.getJSONArray("enum")).append('\n');
        }
        if (schema.has("required")) out.append("Required: ").append(schema.getJSONArray("required")).append('\n');
        return out.append("\n--args JSON|@file|-  Supply the entire argument object.\n")
                .append("--dry-run           Print the request without executing it.\n")
                .append("Exit codes: 0 success, 1 operation failed, 2 arguments, 3 transport.\n").toString();
    }

    private static JSONObject execute(String name, JSONObject args) throws IOException, JSONException {
        final String endpoint = System.getenv(ENDPOINT_ENV);
        if (endpoint == null || !endpoint.contains(":")) {
            throw new IOException("No MagicDesk command channel. Open a new MagicDesk Console or Termux Console.");
        }
        final int separator = endpoint.indexOf(':');
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    Integer.parseInt(endpoint.substring(0, separator))), 5_000);
            socket.setSoTimeout(120_000); // Bounds response observation, never cancels or repeats the command.
            AutomationCommandWire.write(socket.getOutputStream(), new JSONObject()
                    .put("key", endpoint.substring(separator + 1)).put("build", BuildConfig.SOURCE_ID)
                    .put("name", name).put("arguments", args), AutomationCommandWire.REQUEST_LIMIT);
            // One request only. EOF is indeterminate, never a reason to repeat an action.
            return AutomationCommandWire.read(socket.getInputStream(), AutomationCommandWire.RESPONSE_LIMIT);
        }
    }
}
