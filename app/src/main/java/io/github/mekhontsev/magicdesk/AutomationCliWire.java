package io.github.mekhontsev.magicdesk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Native clients transport arguments and bytes; the APK owns CLI syntax and command schemas. */
final class AutomationCliWire {
    static final int MAGIC = 0x8d444301;
    static final int LEASE = 1, INVOKE = 2;
    static final int READ = 1, OUT = 2, ERR = 3, EXIT = 4;

    static String read(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0) throw new IOException("Guest argument input could not be read");
        if (length > limit) throw new IOException("CLI frame exceeds size limit");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    static int serve(DataInputStream in, DataOutputStream out, MagicDeskCli.Executor executor) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 4096) throw new IOException("Invalid CLI argument count");
        String[] argv = new String[count];
        int remaining = AutomationCommandWire.REQUEST_LIMIT;
        for (int i = 0; i < count; i++) {
            argv[i] = read(in, remaining);
            remaining -= argv[i].getBytes(StandardCharsets.UTF_8).length;
            if (argv[i].indexOf('\0') >= 0) throw new IOException("NUL in CLI argument");
        }
        final int[] remainingOutput = {AutomationCommandWire.RESPONSE_LIMIT};
        var stdout = new PrintStream(output(out, OUT, remainingOutput), true, StandardCharsets.UTF_8);
        var stderr = new PrintStream(output(out, ERR, remainingOutput), true, StandardCharsets.UTF_8);
        int status = MagicDeskCli.run(argv, value -> {
            if (!value.equals("-") && !value.startsWith("@")) return value;
            out.writeInt(READ);
            write(out, value);
            // EVENT_WAIT: guest supplies its own stdin/file; socket deadline bounds a missing response.
            return read(in, AutomationCommandWire.REQUEST_LIMIT);
        }, stdout, stderr, executor);
        if (stdout.checkError() || stderr.checkError()) throw new IOException("CLI output delivery failed; outcome unknown");
        out.writeInt(EXIT);
        out.writeInt(status);
        out.flush();
        return status;
    }

    private static OutputStream output(DataOutputStream out, int kind, int[] remaining) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            @Override public void write(byte[] bytes, int start, int count) throws IOException {
                if (count > remaining[0]) throw new IOException("CLI output exceeds size limit");
                remaining[0] -= count;
                out.writeInt(kind);
                out.writeInt(count);
                out.write(bytes, start, count);
                out.flush();
            }
        };
    }
    private AutomationCliWire() { }
}
