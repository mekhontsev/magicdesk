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
    private static final String ID = "transfer_1234567890";
    private final byte[] bytes = "resumable binary \u0000 content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private AutomationFileTransfers transfers() {
        return new AutomationFileTransfers(temporary.getRoot().toPath().resolve("journal"), new Storage());
    }
    private String target() { return temporary.getRoot().toPath().resolve("file.bin").toString(); }
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
        assertFalse(Files.exists(Path.of(target())));
        assertEquals(bytes.length, transfers().execute("files.upload_chunk", chunk()).getLong("offset"));
        assertEquals(bytes.length, transfers().execute("files.upload_begin", begin()).getLong("offset"));
        transfers().execute("files.upload_chunk", chunk());
        transfers().execute("files.upload_commit", id());
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(target())));
        Files.writeString(Path.of(target()), "user changed after commit");
        transfers().execute("files.upload_commit", id());
        transfers().execute("files.upload_abort", id());
        assertEquals("user changed after commit", Files.readString(Path.of(target())));
    }

    @Test public void wrongRetryOffsetDigestAndIdReuseAreRejected() throws Exception {
        transfers().execute("files.upload_begin", begin());
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_chunk", chunk().put("offset", 1)));
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_begin", begin().put("size", 99)));
        transfers().execute("files.upload_chunk", chunk());
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_chunk",
                chunk().put("data", Base64.getEncoder().encodeToString(new byte[bytes.length]))));
        final Path part = temporary.getRoot().toPath().resolve(".part-" + ID);
        Files.write(part, new byte[bytes.length]);
        assertThrows(IOException.class, () -> transfers().execute("files.upload_commit", id()));
        assertFalse(Files.exists(Path.of(target())));
    }

    @Test public void committedMoveCanBeRecoveredBeforeJournalCommit() throws Exception {
        transfers().execute("files.upload_begin", begin());
        transfers().execute("files.upload_chunk", chunk());
        Files.move(temporary.getRoot().toPath().resolve(".part-" + ID), Path.of(target()));
        assertEquals("completed", transfers().execute("files.upload_commit", id()).getString("state"));
    }

    @Test public void downloadRejectsChangedSourceAndChecksDirection() throws Exception {
        Files.write(Path.of(target()), bytes);
        transfers().execute("files.download_begin", id().put("path", target()));
        final JSONObject chunk = transfers().execute("files.download_chunk", id().put("offset", 0));
        assertArrayEquals(bytes, Base64.getDecoder().decode(chunk.getString("data")));
        assertTrue(chunk.getBoolean("eof"));
        assertThrows(IllegalArgumentException.class, () -> transfers().execute("files.upload_abort", id()));
        Files.writeString(Path.of(target()), "changed");
        assertThrows(IOException.class, () -> transfers().execute("files.download_chunk", id().put("offset", 0)));
        transfers().execute("files.download_finish", id());
        assertTrue(Files.exists(Path.of(target())));
    }

    @Test public void existingDestinationNeedsExplicitOverwrite() throws Exception {
        Files.writeString(Path.of(target()), "original");
        transfers().execute("files.upload_begin", begin());
        transfers().execute("files.upload_chunk", chunk());
        assertThrows(IOException.class, () -> transfers().execute("files.upload_commit", id()));
        assertEquals("original", Files.readString(Path.of(target())));
        transfers().execute("files.upload_abort", id());
    }

    @Test public void abortRecoversIfFileWasDeletedBeforeJournalCommit() throws Exception {
        transfers().execute("files.upload_begin", begin());
        Files.delete(temporary.getRoot().toPath().resolve(".part-" + ID));
        assertEquals("aborted", transfers().execute("files.upload_abort", id()).getString("state"));
        assertEquals("aborted", transfers().execute("files.upload_abort", id()).getString("state"));
        assertFalse(Files.exists(Path.of(target())));
    }

    @Test public void abortNeverDeletesReplacementFile() throws Exception {
        transfers().execute("files.upload_begin", begin());
        final Path part = temporary.getRoot().toPath().resolve(".part-" + ID);
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

    private static final class Storage implements AutomationFileTransfers.Storage {
        @Override public FileIdentity stat(String path) throws IOException {
            final var attrs = Files.readAttributes(Path.of(path), java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new FileIdentity(path, 0, attrs.fileKey().hashCode(), attrs.size(), attrs.lastModifiedTime().toMillis());
        }
        @Override public FileIdentity create(String target, String id) throws IOException {
            return stat(Files.createFile(Path.of(target).resolveSibling(".part-" + id)).toString());
        }
        private void verify(FileIdentity file) throws IOException {
            if (!file.sameFile(stat(file.path()))) throw new IOException("identity changed");
        }
        @Override public InputStream read(FileIdentity file, long offset) throws IOException {
            verify(file);
            final var stream = new FileInputStream(file.path());
            stream.getChannel().position(offset);
            return stream;
        }
        @Override public void write(FileIdentity file, long offset, byte[] data) throws IOException {
            verify(file);
            try (RandomAccessFile stream = new RandomAccessFile(file.path(), "rw")) {
                stream.seek(offset); stream.write(data);
            }
        }
        @Override public FileIdentity publish(FileIdentity file, String target, boolean overwrite) throws IOException {
            verify(file);
            if (overwrite) Files.move(Path.of(file.path()), Path.of(target), StandardCopyOption.REPLACE_EXISTING);
            else Files.move(Path.of(file.path()), Path.of(target));
            return stat(target);
        }
        @Override public void delete(FileIdentity file) throws IOException {
            try {
                verify(file);
                Files.delete(Path.of(file.path()));
            } catch (NoSuchFileException absent) {
                // Same retry contract as ShellFileSystem's identity-verified cleanup.
            }
        }
    }
}
