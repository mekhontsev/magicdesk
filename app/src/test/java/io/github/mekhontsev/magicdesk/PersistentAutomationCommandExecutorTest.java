package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PersistentAutomationCommandExecutorTest {
    private static final String MARKER = "__MAGICDESK_TEST__";

    @Test
    public void readsOutputAndCompletionRecordWithoutLosingNewlines()
            throws Exception {
        final PersistentAutomationCommandExecutor.ReadState state = read(
                "one\ntwo\n\n" + MARKER + "7\t/tmp\n");
        final PersistentAutomationCommandExecutor.Completion completion =
                completion(
                        "one\ntwo\n\n" + MARKER + "7\t/tmp\n");

        assertEquals("one\ntwo\n", state.output());
        assertEquals(7, completion.exitCode);
        assertEquals("/tmp", completion.workingDirectory);
    }

    @Test
    public void keepsOutputThatOnlyPartiallyMatchesMarker() throws Exception {
        final String output = "before\n" + MARKER + "not-a-record\nafter";
        final PersistentAutomationCommandExecutor.ReadState state = read(
                output + "\n" + MARKER + "0\t/sdcard\n");

        assertEquals(output, state.output());
    }

    @Test
    public void streamsOnlyVisibleCommandOutput() throws Exception {
        final List<String> streamed = new ArrayList<>();
        final PersistentAutomationCommandExecutor.ReadState state =
                new PersistentAutomationCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8),
                        streamed::add);

        state.read(new ByteArrayInputStream(
                ("one\ntwo\n\n" + MARKER + "0\t/tmp\n")
                        .getBytes(StandardCharsets.UTF_8)));

        assertEquals("one\ntwo\n", String.join("", streamed));
        assertEquals("one\ntwo\n", state.output());
    }

    @Test
    public void streamsTextBeforePossibleMarkerLineIsResolved()
            throws Exception {
        final StringBuilder streamed = new StringBuilder();
        final byte[] encoded = ("ready\n\n" + MARKER + "0\t/tmp\n")
                .getBytes(StandardCharsets.UTF_8);
        final int boundary = "ready\n".getBytes(StandardCharsets.UTF_8).length;
        final InputStream input = new InputStream() {
            private int mOffset;

            @Override
            public int read() {
                if (mOffset == boundary) {
                    assertEquals("ready", streamed.toString());
                }
                return mOffset < encoded.length
                        ? encoded[mOffset++] & 0xff : -1;
            }
        };
        final PersistentAutomationCommandExecutor.ReadState state =
                new PersistentAutomationCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8),
                        streamed::append);

        state.read(input);

        assertEquals("ready\n", streamed.toString());
    }

    private static PersistentAutomationCommandExecutor.ReadState read(
            final String encoded) throws Exception {
        final PersistentAutomationCommandExecutor.ReadState state =
                new PersistentAutomationCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8));
        state.read(new ByteArrayInputStream(
                encoded.getBytes(StandardCharsets.UTF_8)));
        return state;
    }

    private static PersistentAutomationCommandExecutor.Completion completion(
            final String encoded) throws Exception {
        final PersistentAutomationCommandExecutor.ReadState state =
                new PersistentAutomationCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8));
        return state.read(new ByteArrayInputStream(
                encoded.getBytes(StandardCharsets.UTF_8)));
    }
}
