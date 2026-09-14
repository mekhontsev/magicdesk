package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Slave PTY and process birth identity, captured by its owner at creation. */
record PtyEndpoint(long processId, long startTicks, String tty) {
    PtyEndpoint {
        if (processId < 1 || processId > Integer.MAX_VALUE || startTicks < 1
                || tty == null || !tty.matches("/dev/pts/[0-9]+")) {
            throw new IllegalArgumentException("invalid PTY endpoint");
        }
    }

    static PtyEndpoint parse(String value) throws IOException {
        try {
            final String[] fields = value.split(" ", -1);
            if (fields.length != 3) throw new IllegalArgumentException("invalid PTY identity");
            return new PtyEndpoint(Long.parseLong(fields[0]), Long.parseLong(fields[1]), fields[2]);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid PTY identity", error);
        }
    }

    String wireValue() { return processId + " " + startTicks + " " + tty; }
}
