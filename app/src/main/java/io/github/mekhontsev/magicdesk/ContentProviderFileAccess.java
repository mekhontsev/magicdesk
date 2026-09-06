package io.github.mekhontsev.magicdesk;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Transfers a provider's opened descriptor only while the request is live. */
final class ContentProviderFileAccess {
    interface Op<T extends Closeable> {
        T open() throws IOException;
    }

    private ContentProviderFileAccess() {
    }

    static ParcelFileDescriptor open(
            final CancellationSignal signal,
            final Op<ParcelFileDescriptor> operation) throws FileNotFoundException {
        try {
            return openChecked(operation, () -> {
                if (signal != null) {
                    signal.throwIfCanceled();
                }
            });
        } catch (OperationCanceledException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException error) {
            final FileNotFoundException failure = new FileNotFoundException(
                    ShellAccess.usefulMessage(error));
            failure.initCause(error);
            throw failure;
        }
    }

    static <T extends Closeable> T openChecked(
            final Op<T> operation, final Runnable checkCancelled) throws IOException {
        checkCancelled.run();
        final T resource = operation.open();
        try {
            // Opening may block in Binder. If cancelled during that call, we
            // still own the returned descriptor and must close it before failing.
            checkCancelled.run();
            return resource;
        } catch (RuntimeException | Error failure) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (IOException | RuntimeException cleanupFailure) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        }
    }
}
