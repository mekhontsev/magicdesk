package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import io.github.mekhontsev.magicdesk.AutomationFileTransfers.FileIdentity;

/** Privileged IO reuses Files' verified descriptors and non-recursive cleanup. */
final class ShellAutomationTransferStorage implements AutomationFileTransfers.Storage {
    @Override public FileIdentity stat(String path) throws IOException {
        return identity(ShellAccess.getShellFileInfo(path));
    }

    @Override public FileIdentity create(String target, String id) throws IOException {
        final Path path = Path.of(target);
        try (ShellFileCreation creation = ShellAccess.beginShellFileCreation(path.getParent().toString(),
                ".magicdesk-upload-" + id)) {
            final FileIdentity identity = identity(creation.file);
            creation.commit();
            return identity;
        }
    }

    @Override public InputStream read(FileIdentity file, long offset) throws IOException {
        final var stream = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openVerifiedShellFile(verified(file), "r"));
        try {
            stream.getChannel().position(offset);
            return stream;
        } catch (IOException failure) { stream.close(); throw failure; }
    }

    @Override public void write(FileIdentity file, long offset, byte[] data) throws IOException {
        try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(
                ShellAccess.openVerifiedShellFile(verified(file), "rw"))) {
            final var channel = stream.getChannel();
            channel.position(offset);
            final ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    @Override public FileIdentity publish(FileIdentity file, String target, boolean overwrite) throws IOException {
        return identity(ShellAccess.publishVerifiedShellFile(verified(file), target, overwrite));
    }

    @Override public void delete(FileIdentity file) throws IOException {
        // The shell verifies identity and treats an already absent file as completed cleanup.
        ShellAccess.deleteVerifiedShellFile(file.path(), file.device(), file.inode());
    }

    private static ShellFileInfo verified(FileIdentity file) throws IOException {
        final ShellFileInfo actual = ShellAccess.getShellFileInfo(file.path());
        if (!file.sameFile(identity(actual))) throw new IOException("file identity changed");
        return actual;
    }

    private static FileIdentity identity(ShellFileInfo file) throws IOException {
        if (file == null || file.symbolicLink || !file.isRegularFile()) throw new IOException("ordinary file required");
        return new FileIdentity(file.absolutePath, file.deviceId, file.inode, file.size, file.modified);
    }
}
