package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bridges explicit file copy/paste actions to Android URI ClipData. */
final class FileClipboardInterop {
    enum PasteKind {
        NONE,
        INTERNAL_PATHS,
        ANDROID_URIS
    }

    static final class PasteSource {
        final PasteKind kind;
        final FileOperationClipboard.Snapshot files;
        final List<Uri> uris;

        PasteSource(
                final PasteKind kind,
                final FileOperationClipboard.Snapshot files,
                final List<Uri> uris) {
            this.kind = kind;
            this.files = files;
            this.uris = uris;
        }
    }

    private FileClipboardInterop() {
    }

    static synchronized FileOperationClipboard.Snapshot storeShellFiles(
            final Context context,
            final List<ShellFileInfo> files,
            final FileOperationClipboard.Mode mode) {
        final FileOperationClipboard.Snapshot previous =
                FileOperationClipboard.snapshot();
        final List<String> paths = new ArrayList<>(files.size());
        boolean publishable = AndroidClipboardGateway.acceptsUriItemCount(
                files.size());
        for (final ShellFileInfo file : files) {
            paths.add(file.absolutePath);
            if (file.directory || file.symbolicLink || !file.readable) {
                publishable = false;
            }
        }
        final FileOperationClipboard.Snapshot stored =
                FileOperationClipboard.set(paths, mode);
        boolean published = false;
        if (publishable) {
            try {
                final List<AndroidClipboardGateway.UriItem> items =
                        new ArrayList<>(files.size());
                for (final ShellFileInfo file : files) {
                    items.add(new AndroidClipboardGateway.UriItem(
                            ShellFileGrantStore.create(context, file, false),
                            file.mimeType));
                }
                published = publish(context, items, stored);
            } catch (RuntimeException ignored) {
                // Android interop is additive; internal copy/move still works.
            }
        }
        if (!published) {
            clearReplacedSystemClip(context, previous);
        }
        return FileOperationClipboard.snapshot();
    }

    static synchronized FileOperationClipboard.Snapshot storeDesktopFile(
            final Context context,
            final DesktopFile file,
            final String absolutePath,
            final FileOperationClipboard.Mode mode) {
        final FileOperationClipboard.Snapshot previous =
                FileOperationClipboard.snapshot();
        final FileOperationClipboard.Snapshot stored =
                FileOperationClipboard.set(List.of(absolutePath), mode);
        boolean published = false;
        if (!file.directory) {
            try {
                published = publish(
                        context,
                        List.of(new AndroidClipboardGateway.UriItem(
                                file.uri, file.mimeType)),
                        stored);
            } catch (RuntimeException ignored) {
                // Android interop is additive; internal copy/move still works.
            }
        }
        if (!published) {
            clearReplacedSystemClip(context, previous);
        }
        return FileOperationClipboard.snapshot();
    }

    static synchronized boolean canPaste(final Context context) {
        final FileOperationClipboard.Snapshot files = reconciledFiles(context);
        if (!files.isEmpty()) {
            return true;
        }
        return AndroidClipboardGateway.get(context).metadata().mayContainUris();
    }

    static synchronized PasteSource resolvePaste(final Context context) {
        final AndroidClipboardGateway clipboard =
                AndroidClipboardGateway.get(context);
        final FileOperationClipboard.Snapshot files = reconciledFiles(context);
        if (!files.isEmpty()) {
            return new PasteSource(
                    PasteKind.INTERNAL_PATHS,
                    files,
                    Collections.emptyList());
        }
        final AndroidClipboardGateway.UriReadResult android =
                clipboard.readUris();
        if (!android.uris.isEmpty()) {
            return new PasteSource(
                    PasteKind.ANDROID_URIS,
                    FileOperationClipboard.snapshot(),
                    android.uris);
        }
        return new PasteSource(
                PasteKind.NONE,
                FileOperationClipboard.snapshot(),
                Collections.emptyList());
    }

    static synchronized void completeMove(final long generation) {
        FileOperationClipboard.clearIfGeneration(generation);
        final Context context = MagicDeskApplication.applicationContext();
        if (context != null) {
            AndroidClipboardGateway.get(context)
                    .clearFileOperation(generation);
        }
    }

    static synchronized AndroidClipboardGateway.OperationResult clear(
            final Context context) {
        final FileOperationClipboard.Snapshot files =
                FileOperationClipboard.snapshot();
        final AndroidClipboardGateway clipboard =
                AndroidClipboardGateway.get(context);
        final AndroidClipboardGateway.Metadata before = clipboard.metadata();
        final AndroidClipboardGateway.OperationResult cleared =
                clipboard.clear();
        if (cleared.successful
                && before.belongsToFileOperation(files.generation)) {
            FileOperationClipboard.clearIfGeneration(files.generation);
        }
        return cleared;
    }

    private static FileOperationClipboard.Snapshot reconciledFiles(
            final Context context) {
        final FileOperationClipboard.Snapshot files =
                FileOperationClipboard.snapshot();
        if (files.isEmpty() || !files.systemPublished) {
            return files;
        }
        final AndroidClipboardGateway.Metadata android =
                AndroidClipboardGateway.get(context).metadata();
        if (android.access == AndroidClipboardGateway.Access.DENIED
                || android.access == AndroidClipboardGateway.Access.FAILED
                || android.access == AndroidClipboardGateway.Access.UNAVAILABLE
                || android.belongsToFileOperation(files.generation)) {
            return files;
        }
        FileOperationClipboard.clearIfGeneration(files.generation);
        return FileOperationClipboard.snapshot();
    }

    private static boolean publish(
            final Context context,
            final List<AndroidClipboardGateway.UriItem> items,
            final FileOperationClipboard.Snapshot files) {
        final AndroidClipboardGateway.OperationResult result =
                AndroidClipboardGateway.get(context).writeUris(
                        "MagicDesk files", items, files.generation);
        if (result.successful) {
            FileOperationClipboard.markSystemPublished(files.generation);
        }
        return result.successful;
    }

    private static void clearReplacedSystemClip(
            final Context context,
            final FileOperationClipboard.Snapshot previous) {
        if (previous.systemPublished) {
            AndroidClipboardGateway.get(context)
                    .clearFileOperation(previous.generation);
        }
    }
}
