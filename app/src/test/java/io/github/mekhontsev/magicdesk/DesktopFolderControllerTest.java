package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopFolderControllerTest {
    @Test
    public void eachBinderCallbackCapturesItsRegistrationGeneration() throws IOException {
        final String source = source();
        assertFalse(source.contains("mObserverCallback"));
        assertTrue(source.contains("observerCallback(generation),"));
        final String callback = between(source,
                "private IDesktopFolderObserverCallback observerCallback(",
                "private boolean isCurrentObserver(");
        assertTrue(callback.contains("mHandler.post(() ->"));
        assertTrue(callback.contains("if (!isCurrentObserver(generation))"));
        assertTrue(callback.indexOf("if (!isCurrentObserver(generation))")
                < callback.indexOf("scheduleMetadataRefresh(relativePath)"));
        assertTrue(source.contains(
                "return !mReleased && mStarted && generation == mObserverGeneration;"));
    }

    @Test
    public void metadataWorkAndDeliveryRejectAStoppedOrReplacedObserver() throws IOException {
        final String metadata = between(source(),
                "private final Runnable mObservedMetadataRefresh",
                "private IDesktopFolderObserverCallback observerCallback(");
        final int worker = metadata.indexOf("mExecutor.execute(() ->");
        assertTrue(worker >= 0);
        final String completion = metadata.substring(worker);
        assertTrue(completion.contains("if (!isCurrentObserver(generation))"));
        assertTrue(completion.indexOf("if (!isCurrentObserver(generation))")
                < completion.indexOf("DesktopStateStore.reload()"));
        assertTrue(completion.contains("if (isCurrentObserver(generation))"));
    }

    @Test
    public void stopInvalidatesCallbacksAndDiscardsDebouncedMetadata() throws IOException {
        final String source = source();
        final String stop = between(source, "void stop()", "void release()");
        assertTrue(stop.contains("mStarted = false;"));
        assertTrue(stop.contains("mLoadGeneration++;"));
        assertTrue(stop.contains("mLoaded = false;"));
        assertTrue(stop.contains("closeObserver();"));
        assertTrue(stop.contains("mHandler.removeCallbacks(mObservedRefresh);"));
        assertTrue(stop.contains("mHandler.removeCallbacks(mObservedMetadataRefresh);"));
        assertTrue(stop.contains("mObservedStateChanged = false;"));
        assertTrue(stop.contains("mObservedWallpaperChanged = false;"));
        assertTrue(between(source, "private void closeObserver()", "private void postFailure(")
                .contains("mObserverGeneration++;"));
        assertTrue(source.contains("private volatile int mObserverGeneration;"));
        assertTrue(source.contains("private volatile boolean mStarted;"));
    }

    @Test
    public void desktopTransfersReuseTheSharedProgressAndRemoteLifecycleOwner()
            throws IOException {
        final String source = source();
        assertFalse(source.contains("IFileOperationCallback"));
        assertFalse(source.contains("mFileOperationOwner"));
        assertFalse(source.contains("ShellAccess.startShellFileOperation("));
        assertFalse(source.contains("FileClipboardInterop.completeMove("));
        assertTrue(source.contains("new FileManagerOperationController(mActivity, this::onTransferCompleted)"));
        assertTrue(source.contains("operations().startRemote("));
        assertTrue(source.contains("paths, destination, copy ? -1L : clipboardGeneration"));
        assertTrue(between(source, "void stop()", "void release()")
                .contains("mOperations.close();"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopFolderController.java"));
    }

    private static String between(final String source, final String start, final String end) {
        final int first = source.indexOf(start);
        final int last = source.indexOf(end, first + start.length());
        assertTrue("method boundaries exist", first >= 0 && last > first);
        return source.substring(first, last);
    }
}
