package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class PersistentConsoleCommandExecutorTest {
    private static final String MARKER = "__MAGICDESK_TEST__";

    @Test
    public void readsOutputAndCompletionRecordWithoutLosingNewlines()
            throws Exception {
        final PersistentConsoleCommandExecutor.ReadState state = read(
                "one\ntwo\n\n" + MARKER + "7\t/tmp\n");
        final PersistentConsoleCommandExecutor.Completion completion =
                completion(
                        "one\ntwo\n\n" + MARKER + "7\t/tmp\n");

        assertEquals("one\ntwo\n", state.output());
        assertEquals(7, completion.exitCode);
        assertEquals("/tmp", completion.workingDirectory);
    }

    @Test
    public void keepsOutputThatOnlyPartiallyMatchesMarker() throws Exception {
        final String output = "before\n" + MARKER + "not-a-record\nafter";
        final PersistentConsoleCommandExecutor.ReadState state = read(
                output + "\n" + MARKER + "0\t/sdcard\n");

        assertEquals(output, state.output());
    }

    private static PersistentConsoleCommandExecutor.ReadState read(
            final String encoded) throws Exception {
        final PersistentConsoleCommandExecutor.ReadState state =
                new PersistentConsoleCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8));
        state.read(new ByteArrayInputStream(
                encoded.getBytes(StandardCharsets.UTF_8)));
        return state;
    }

    private static PersistentConsoleCommandExecutor.Completion completion(
            final String encoded) throws Exception {
        final PersistentConsoleCommandExecutor.ReadState state =
                new PersistentConsoleCommandExecutor.ReadState(
                        ("\n" + MARKER).getBytes(StandardCharsets.UTF_8));
        return state.read(new ByteArrayInputStream(
                encoded.getBytes(StandardCharsets.UTF_8)));
    }
}
