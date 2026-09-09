package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import io.github.mekhontsev.magicdesk.AutomationFileTransfers.FileIdentity;

public final class AutomationFileTransfersTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Rule public final TestFileSystem files = new TestFileSystem();
    private static final String ID = "transfer_1234567890";
    private final byte[] bytes = "resumable binary \u0000 content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private AutomationFileTransfers transfers() {
        return new AutomationFileTransfers(temporary.getRoot().toPath().resolve("journal"), new Storage());
    }
    private String target() { return files.root().resolve("file.bin").toString(); }
    private JSONObject id() throws Exception { return new JSONObject().put("transferId", ID); }
    private JSONObject begin() throws Exception {
        return id().put("path", target()).put("size", bytes.length)
                .put("sha256", AutomationFileTransfers.digest(new ByteArrayInputStream(bytes)));
    }
    private JSONObject chunk() throws Exception {
        return id().put("offset", 0).put("data", Base64.getEncoder().encodeToString(bytes));
    }

    @Test public void uploadResumesAfterProcessRestartAndCommitRetryDoesNotRewrite() throws Exception {
        transfers().execute("files.upload_begin", begin());
        assertFalse(Files.exists(files.path(target())));
        assertEquals(bytes.length, transfers().execute("files.upload_chunk", chunk()).getLong("offset"));
        assertEquals(bytes.length, transfers().execute("files.upload_begin", begin()).getLong("offset"));
        transfers().execute("files.upload_chunk", chunk());
        transfers().execute("files.upload_commit", id());
        assertArrayEquals(bytes, Files.readAllBytes(files.path(target())));
        Files.writeString(files.path(target()), "user changed after commit");
        transfers().execute("files.upload_commit", id());
        transfers().execute("files.upload_abort", id());
        assertEquals("user changed after commit", Files.readString(files.path(target())));
    }

    @Test public void wrongRetryOffsetDigestAndIdReuseAreRejected() throws Exception {
        transfers().execute("files.upload_begin", begin());
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_chunk", chunk().put("offset", 1)));
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_begin", begin().put("size", 99)));
        transfers().execute("files.upload_chunk", chunk());
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_chunk",
                chunk().put("data", Base64.getEncoder().encodeToString(new byte[bytes.length]))));
        final Path part = files.root().resolve(".part-" + ID);
        Files.write(part, new byte[bytes.length]);
        assertThrows(IOException.class, () -> transfers().execute("files.upload_commit", id()));
        assertFalse(Files.exists(files.path(target())));
    }

    @Test public void committedMoveCanBeRecoveredBeforeJournalCommit() throws Exception {
        transfers().execute("files.upload_begin", begin());
        transfers().execute("files.upload_chunk", chunk());
        Files.move(files.root().resolve(".part-" + ID), files.path(target()));
        assertEquals("completed", transfers().execute("files.upload_commit", id()).getString("state"));
    }

    @Test public void downloadRejectsChangedSourceAndChecksDirection() throws Exception {
        Files.write(files.path(target()), bytes);
        transfers().execute("files.download_begin", id().put("path", target()));
        final JSONObject chunk = transfers().execute("files.download_chunk", id().put("offset", 0));
        assertArrayEquals(bytes, Base64.getDecoder().decode(chunk.getString("data")));
        assertTrue(chunk.getBoolean("eof"));
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_abort", id()));
        Files.writeString(files.path(target()), "changed");
        assertThrows(IOException.class, () -> transfers().execute("files.download_chunk", id().put("offset", 0)));
        transfers().execute("files.download_finish", id());
        assertTrue(Files.exists(files.path(target())));
    }

    @Test public void existingDestinationNeedsExplicitOverwrite() throws Exception {
        Files.writeString(files.path(target()), "original");
        transfers().execute("files.upload_begin", begin());
        transfers().execute("files.upload_chunk", chunk());
        assertThrows(IOException.class, () -> transfers().execute("files.upload_commit", id()));
        assertEquals("original", Files.readString(files.path(target())));
        transfers().execute("files.upload_abort", id());
    }

    @Test public void abortRecoversIfFileWasDeletedBeforeJournalCommit() throws Exception {
        transfers().execute("files.upload_begin", begin());
        Files.delete(files.root().resolve(".part-" + ID));
        assertEquals("aborted", transfers().execute("files.upload_abort", id()).getString("state"));
        assertEquals("aborted", transfers().execute("files.upload_abort", id()).getString("state"));
        assertFalse(Files.exists(files.path(target())));
    }

    @Test public void abortNeverDeletesReplacementFile() throws Exception {
        transfers().execute("files.upload_begin", begin());
        final Path part = files.root().resolve(".part-" + ID);
        Files.move(part, part.resolveSibling("original.bin"));
        Files.writeString(part, "replacement");
        assertThrows(IOException.class, () -> transfers().execute("files.upload_abort", id()));
        assertEquals("replacement", Files.readString(part));
    }

    @Test public void rejectsTraversalAndUnboundedMetadata() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_begin", begin().put("transferId", "../invalid")));
        for (int i = 0; i < AutomationFileTransfers.MAX_TRANSFERS; i++) {
            transfers().execute("files.upload_begin", begin().put("transferId", "transfer_1234567890_" + i));
        }
        assertThrows(IOException.class, () -> transfers().execute("files.upload_begin", begin()));
    }

    private final class Storage implements AutomationFileTransfers.Storage {
        @Override public FileIdentity stat(String path) throws IOException {
            final var attrs = Files.readAttributes(files.path(path), java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new FileIdentity(path, 0, attrs.fileKey().hashCode(), attrs.size(), attrs.lastModifiedTime().toMillis());
        }
        @Override public FileIdentity create(String target, String id) throws IOException {
            return stat(Files.createFile(files.path(target).resolveSibling(".part-" + id)).toString());
        }
        private void verify(FileIdentity file) throws IOException {
            if (!file.sameFile(stat(file.path()))) throw new IOException("identity changed");
        }
        @Override public InputStream read(FileIdentity file, long offset) throws IOException {
            verify(file);
            final var channel = java.nio.channels.FileChannel.open(files.path(file.path()));
            channel.position(offset);
            return java.nio.channels.Channels.newInputStream(channel);
        }
        @Override public void write(FileIdentity file, long offset, byte[] data) throws IOException {
            verify(file);
            try (var channel = java.nio.channels.FileChannel.open(
                    files.path(file.path()), StandardOpenOption.WRITE)) {
                channel.position(offset);
                final var buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
        @Override public FileIdentity publish(FileIdentity file, String target, boolean overwrite) throws IOException {
            verify(file);
            if (overwrite) Files.move(files.path(file.path()), files.path(target), StandardCopyOption.REPLACE_EXISTING);
            else Files.move(files.path(file.path()), files.path(target));
            return stat(target);
        }
        @Override public void delete(FileIdentity file) throws IOException {
            try {
                verify(file);
                Files.delete(files.path(file.path()));
            } catch (NoSuchFileException absent) {
                // Same retry contract as ShellFileSystem's identity-verified cleanup.
            }
        }
    }
}
