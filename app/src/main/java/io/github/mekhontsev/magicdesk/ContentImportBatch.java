package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Immutable import request; scheduling and URI grants belong to its caller. */
final class ContentImportBatch<T> {
    interface Importer<T> {
        void copy(T item, BooleanSupplier cancelled) throws IOException;
    }

    static final class Result {
        final int total;
        final int copied;
        final int failed;
        final int skipped;
        final boolean cancelled;
        final Throwable firstFailure;

        private Result(final int total, final int copied, final int failed,
                final boolean cancelled, final Throwable firstFailure) {
            this.total = total;
            this.copied = copied;
            this.failed = failed;
            this.skipped = total - copied - failed;
            this.cancelled = cancelled;
            this.firstFailure = firstFailure;
        }

        static Result notStarted(final int total, final Throwable failure) {
            if (total < 0) {
                throw new IllegalArgumentException("negative import item count");
            }
            return new Result(total, 0, 0, false, Objects.requireNonNull(failure));
        }

        boolean isComplete() {
            return copied == total && !cancelled && firstFailure == null;
        }

        private Result withCompletionFailure(final Throwable failure) {
            if (failure == null || failure == firstFailure) {
                return this;
            }
            final Throwable combined;
            if (firstFailure == null) {
                combined = failure;
            } else {
                combined = new IOException(firstFailure.getMessage(), firstFailure);
                combined.addSuppressed(failure);
            }
            return new Result(total, copied, failed, cancelled, combined);
        }
    }

    private final List<T> mItems;
    private final Importer<T> mImporter;

    ContentImportBatch(final List<T> items, final Importer<T> importer) {
        mItems = List.copyOf(items);
        mImporter = Objects.requireNonNull(importer);
    }

    int size() {
        return mItems.size();
    }

    Result finish(final Result result, final Throwable failure) {
        return result == null ? Result.notStarted(size(), failure)
                : result.withCompletionFailure(failure);
    }

    Result run(final BooleanSupplier cancelled, final IntConsumer progress) {
        int copied = 0;
        int failed = 0;
        boolean stopped = false;
        Throwable firstFailure = null;
        for (final T item : mItems) {
            if (isCancelled(cancelled)) {
                stopped = true;
                break;
            }
            try {
                mImporter.copy(item, cancelled);
                copied++;
            } catch (IOException | RuntimeException error) {
                stopped = isCancelled(cancelled);
                // Cooperative cancellation skips this item. Preserve real read or
                // rollback failures even if cancellation arrived at the same time.
                if (!stopped || !(error instanceof InterruptedIOException)
                        || error.getSuppressed().length > 0) {
                    failed++;
                    if (firstFailure == null) {
                        firstFailure = error;
                    }
                }
            }
            if (progress != null) {
                try {
                    progress.accept(copied + failed);
                } catch (RuntimeException error) {
                    return new Result(size(), copied, failed, stopped, firstFailure)
                            .withCompletionFailure(error);
                }
            }
            if (stopped) {
                break;
            }
        }
        return new Result(size(), copied, failed, stopped, firstFailure);
    }

    private static boolean isCancelled(final BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted()
                || (cancelled != null && cancelled.getAsBoolean());
    }
}
