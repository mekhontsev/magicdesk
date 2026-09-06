package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileManagerContractTest {
    @Test
    public void directoryReadsCaptureOptionsAndRejectStalePublication() throws IOException {
        final String source = fileManagerSource();
        final String load = source.substring(source.indexOf("private void loadDirectory("),
                source.indexOf("private void clearFileListing()"));
        assertTrue(load.contains("FileDirectoryReader.read("));
        assertFalse(load.contains("ShellAccess.listShellDirectory("));
        assertTrue(load.indexOf("new FileDirectoryReader.Request(") < load.indexOf("mWorker.execute("));
        assertTrue(load.contains("path, mShowHidden, mSortMode, mSortAscending"));
        assertTrue(load.contains("request, () -> mDestroyed || generation != mLoadGeneration.get()"));
        final String delivery = load.substring(load.indexOf("runOnUiThread("));
        assertTrue(delivery.indexOf("generation != mLoadGeneration.get()")
                < delivery.indexOf("mCurrentPath = canonicalPath"));
        final String failed = load.substring(load.indexOf("catch (IOException | RuntimeException error)"));
        assertTrue(failed.contains("generation == mLoadGeneration.get()"));
        assertTrue(failed.contains("clearFileListing();"));
    }

    @Test
    public void unavailableShellInvalidatesReadsAndClearsSelectionWithListing() throws IOException {
        final String source = fileManagerSource();
        final String state = source.substring(source.indexOf("public void onShellStateChanged("),
                source.indexOf("public void onBack()"));
        assertTrue(state.contains("mLoadGeneration.incrementAndGet();"));
        assertTrue(state.contains("closeDirectoryObserver();"));
        assertTrue(state.contains("mSearch.cancel();"));
        assertTrue(state.contains("clearFileListing();"));
        final String load = source.substring(source.indexOf("private void loadDirectory("),
                source.indexOf("private void clearFileListing()"));
        assertTrue(load.indexOf("mLoadGeneration.incrementAndGet()") < load.indexOf("if (!ShellAccess.isReady())"));
        assertTrue(load.indexOf("closeDirectoryObserver()") < load.indexOf("if (!ShellAccess.isReady())"));
        final String clear = source.substring(source.indexOf("private void clearFileListing()"),
                source.indexOf("private void renderFiles()"));
        assertTrue(clear.contains("mFiles.clear();"));
        assertTrue(clear.contains("mDesktopEntries.clear();"));
        assertTrue(clear.contains("mSelected.clear();"));
        assertTrue(clear.contains("mSelectionAnchorPath = null;"));
        assertTrue(clear.contains("renderFiles();"));
    }

    @Test
    public void directoryObserverChecksItsOwnerAtBothQueueBoundaries() throws IOException {
        final String source = fileManagerSource();
        final String observe = source.substring(source.indexOf("private void observeDirectory("),
                source.indexOf("private long selectedFileSize()"));
        assertFalse(source.contains("mDirectoryCallback"));
        assertTrue(observe.contains("final int observerGeneration = mDirectoryObserverGeneration;"));
        assertTrue(observe.contains("absolutePath, observerGeneration"));
        final String schedule = observe.substring(observe.indexOf("private void scheduleObservedRefresh("));
        final int guard = schedule.indexOf("observerGeneration != mDirectoryObserverGeneration");
        final int enqueue = schedule.indexOf("mView.root().post(");
        assertTrue(guard >= 0 && guard < enqueue);
        final String delivery = schedule.substring(enqueue, schedule.indexOf("private void closeDirectoryObserver()"));
        assertTrue(delivery.indexOf("observerGeneration != mDirectoryObserverGeneration")
                < delivery.indexOf("mDirectoryRefreshScheduled = false;"));
        final String close = schedule.substring(schedule.indexOf("private void closeDirectoryObserver()"));
        assertTrue(close.contains("mDirectoryObserverGeneration++;"));
        assertTrue(close.contains("mDirectoryRefreshScheduled = false;"));
    }

    @Test
    public void automaticRefreshAndSummaryUpdatesPreserveOperationMessages() throws IOException {
        final String source = fileManagerSource();
        final String refresh = source.substring(source.indexOf("public void onRefresh()"),
                source.indexOf("public void onNavigate("));
        assertTrue(refresh.substring(0, refresh.indexOf("private void refreshContents()"))
                .contains("mView.clearStatus();"));
        assertFalse(refresh.substring(refresh.indexOf("private void refreshContents()"))
                .contains("mView.clearStatus();"));
        final String summary = source.substring(source.indexOf("private void updateSelectionSummary("),
                source.indexOf("private void startSearch("));
        assertTrue(summary.contains("mView.setSummary("));
        assertFalse(summary.contains("mView.setStatus("));
        final String imported = source.substring(source.indexOf("mImporter ="),
                source.indexOf("mSearch ="));
        assertTrue(imported.contains("mView.setStatus("));
        assertTrue(imported.contains("refreshContents();"));
        assertFalse(imported.contains("onRefresh();"));
    }

    @Test
    public void creatingEntryCapturesDestinationBeforeQueueing() throws IOException {
        final String source = fileManagerSource();
        final String create = source.substring(source.indexOf("private void createEntry("),
                source.indexOf("private void renameEntry("));
        assertTrue(create.contains("final String destination = mCurrentPath;"));
        assertTrue(create.indexOf("final String destination") < create.indexOf("runAsync("));
        assertTrue(create.contains("destination, name, directory"));
        assertFalse(create.contains("mCurrentPath, name, directory"));
    }

    private static String fileManagerSource() throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/FileManagerActivity.java"));
    }

    @Test
    public void fileMimeMetadataTravelsWithItsUriThroughDragAndClipboard() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String store = Files.readString(sources.resolve("ShellFileGrantStore.java"));
        assertTrue(store.contains("List<AndroidContentPayload.UriItem> createReadOnlySelection("));
        assertTrue(store.contains("prepared.add(new Entry(file, false)), file.mimeType"));
        for (final String name : new String[] {"FileClipboardInterop", "FileManagerActivity"}) {
            final String source = Files.readString(sources.resolve(name + ".java"));
            assertTrue(source.contains("List<AndroidContentPayload.UriItem> items"));
        }
        final String drag = Files.readString(sources.resolve("FileDragPayload.java"));
        assertTrue(drag.contains("List<AndroidContentPayload.UriItem> shareableItems"));
        final String desktop = Files.readString(sources.resolve("DesktopWorkspaceController.java"));
        assertTrue(desktop.contains("new AndroidContentPayload.UriItem(file.uri, file.mimeType)"));
        final String content = Files.readString(sources.resolve("AndroidContentPayload.java"));
        final String conversion = content.substring(content.indexOf("static AndroidContentPayload drag("),
                content.indexOf("static AndroidContentPayload create("));
        assertTrue(conversion.contains("List<UriItem> uriItems"));
        assertFalse(conversion.contains("\"*/*\""));
    }

    @Test
    public void fileDragAndClipboardShareThePublicationBoundary() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String files = Files.readString(sources.resolve("FileManagerActivity.java"));
        final String drag = files.substring(files.indexOf("public boolean onStartDrag("),
                files.indexOf("public boolean onDrop("));
        final String clipboard = Files.readString(sources.resolve("FileClipboardInterop.java"));
        for (final String producer : new String[] {drag, clipboard}) {
            assertTrue(producer.contains("ShellFileGrantStore.createReadOnlySelection("));
            assertTrue(producer.contains("finally {"));
            assertTrue(producer.contains("ShellFileGrantStore.discardUnpublished("));
            assertFalse(producer.contains("ShellFileGrantStore.create("));
        }
        final String view = Files.readString(sources.resolve("FileManagerView.java"));
        assertTrue(view.contains("boolean onStartDrag("));
        assertTrue(view.contains("listener::onStartDrag"));
        assertTrue(files.contains("return source.startDragAndDrop("));
    }

    @Test
    public void clipboardMetadataIsPreparedBeforePublishing() throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidClipboardGateway.java"));
        final String write = source.substring(source.indexOf("private OperationResult setPrimaryClip("),
                source.indexOf("private OperationResult writeContent("));
        final int metadata = write.indexOf("metadata(clip.getDescription(), clip.getItemCount())");
        assertTrue(metadata >= 0);
        assertTrue(metadata < write.indexOf("mClipboard.setPrimaryClip(clip)"));
    }

    @Test
    public void manifestProtectsExportedFileManagerAndProvider()
            throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final int activity = manifest.indexOf(
                "android:name=\".FileManagerActivity\"");
        final int activityEnd = manifest.indexOf("/>", activity);
        final String declaration = manifest.substring(activity, activityEnd);
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));

        final int provider = manifest.indexOf(
                "android:name=\".ShellFileProvider\"");
        final int providerEnd = manifest.indexOf("/>", provider);
        final String providerDeclaration = manifest.substring(
                provider, providerEnd);
        assertTrue(providerDeclaration.contains(
                "android:exported=\"false\""));
        assertTrue(providerDeclaration.contains(
                "android:grantUriPermissions=\"true\""));
    }

    @Test
    public void fileServiceExposesTypedOperationsWithExplicitOwnership() throws IOException {
        final String aidl = Files.readString(
                Path.of("src/main/aidl/io/github/mekhontsev/magicdesk/"
                        + "IShizukuCommandService.aidl"),
                StandardCharsets.UTF_8);
        assertTrue(aidl.contains("listShellDirectory("));
        assertTrue(aidl.contains(") = 49;"));
        assertTrue(aidl.contains("cancelShellFileOperation(long operationId) = 55;"));
        assertTrue(aidl.contains("createAvailableShellEntry("));
        assertTrue(aidl.contains("boolean directory) = 57;"));
        assertTrue(aidl.contains("long inode) = 56;"));
        assertTrue(aidl.contains("deleteVerifiedShellFile(String absolutePath, long deviceId, long inode) = 117;"));
        assertTrue(aidl.contains("IShellDirectoryObserverCallback callback) = 66;"));
        assertTrue(aidl.contains("IShellDirectoryObserverCallback callback) = 67;"));
        assertTrue(aidl.contains("long startShellFileSearch("));
        assertTrue(aidl.contains("IBinder ownerToken) = 68;"));
        assertTrue(aidl.contains("oneway void cancelShellFileSearch(long searchId) = 69;"));
        assertFalse(aidl.substring(
                aidl.indexOf("long startShellFileOperation("),
                aidl.indexOf(") = 54;")).contains("boolean replace"));
    }

    @Test
    public void asynchronousActivityFailuresCheckTheWindowWhenDelivered() throws IOException {
        final String source = fileManagerSource();
        final String delivery = source.substring(source.indexOf("private void showAndroidErrorMessage("),
                source.indexOf("private boolean startFileDrag("));
        assertTrue(delivery.indexOf("runOnUiThread(") < delivery.indexOf("if (!mDestroyed)"));
        assertTrue(delivery.indexOf("if (!mDestroyed)") < delivery.indexOf("mView.setStatus("));
    }

    @Test
    public void importCleanupIsVerifiedAndNotARecursiveOperation() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String transfer = Files.readString(sources.resolve("ContentUriTransfer.java"));
        final String importer = Files.readString(sources.resolve("FileManagerImportController.java"));
        assertFalse(transfer.contains("startShellFileOperation("));
        assertFalse(importer.contains("startShellFileOperation("));
        final String entries = Files.readString(sources.resolve("DesktopEntryFile.java"));
        assertTrue(entries.contains("ShellAccess.beginShellFileCreation("));
        assertFalse(entries.contains("ShellAccess.deleteDesktopEntry("));
        final String filesystem = Files.readString(sources.resolve("ShellFileSystem.java"));
        final String cleanup = filesystem.substring(filesystem.indexOf("void deleteVerifiedFile("),
                filesystem.indexOf("ShellFileInfo create("));
        assertTrue(cleanup.contains("Os.lstat("));
        assertTrue(cleanup.contains("OsConstants.S_ISREG(stat.st_mode)"));
        assertTrue(cleanup.contains("stat.st_dev != deviceId || stat.st_ino != inode"));
        assertFalse(cleanup.contains("FileTreeDeletion"));
    }

    @Test
    public void shellWorkspaceActivitiesRequireTaskPermission()
            throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        assertProtectedActivity(manifest, ".TaskManagerActivity");
        assertProtectedActivity(manifest, ".AppLogViewerActivity");
    }

    @Test
    public void openWithKeepsBlockingDiscoveryOutsideTheUiController() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String controller = Files.readString(sources.resolve("FileOpenWithController.java"));
        assertFalse(controller.contains("queryIntentActivities("));
        assertFalse(controller.contains("resolveActivity("));
        assertFalse(controller.contains("DesktopApplicationRepository.queryHandlers("));
        assertFalse(controller.contains("ShellAccess.setPreferredFileHandler("));
        assertTrue(controller.contains("request.submit(cancelled -> FileHandlerRepository.load("));
        assertTrue(controller.contains("request.deliver("));
        assertTrue(controller.contains("!dialog.isShowing()"));
        assertFalse(controller.contains("Executors."));
        final String repository = Files.readString(sources.resolve("FileHandlerRepository.java"));
        assertTrue(repository.contains("ShellAccess.setPreferredFileHandler("));
        assertFalse(repository.contains("import android.app.Activity;"));
        assertFalse(repository.contains("import android.widget."));
    }

    @Test
    public void fileProvidersShareCancellationAndErrorSemantics() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        for (final String name : new String[] {"ShellFileProvider", "DesktopFileProvider"}) {
            final String provider = Files.readString(sources.resolve(name + ".java"));
            assertTrue(provider.contains("ContentProviderFileAccess.open(signal,"));
            assertFalse(provider.contains("new FileNotFoundException(\n"));
        }
        final String access = Files.readString(sources.resolve("ContentProviderFileAccess.java"));
        assertTrue(access.contains("signal.throwIfCanceled();"));
        assertTrue(access.indexOf("catch (OperationCanceledException cancelled)")
                < access.indexOf("catch (IOException | RuntimeException error)"));
        assertTrue(Files.readString(sources.resolve("ShellFileProvider.java"))
                .contains("Binder.restoreCallingIdentity(identity);"));
    }

    @Test
    public void importsLeaveNameReservationToTheFilesystem() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String desktop = Files.readString(sources.resolve("DesktopFolderController.java"));
        assertFalse(desktop.contains("listShellDirectory("));
        assertFalse(desktop.contains("uniqueImportName("));
        assertTrue(desktop.contains("ContentUriTransfer.prepareUris("));
        final String files = Files.readString(sources.resolve("FileManagerImportController.java"));
        assertTrue(files.contains("ContentUriTransfer.prepareUris("));
        assertFalse(files.contains("safeName("));
        final String transfer = Files.readString(sources.resolve("ContentUriTransfer.java"));
        assertTrue(transfer.contains("(uri, cancelled) -> importUri(resolver, uri, destination, cancelled)"));
        assertTrue(transfer.contains("safeFileName(displayName("));
        assertTrue(transfer.contains("ShellAccess.beginShellFileCreation("));
    }

    @Test
    public void contentImportSourcesAreCapturedBeforeQueueingOnEveryUi() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        for (final String name : new String[]{"FileManagerImportController", "DesktopFolderController",
                "DesktopContentReceiverActivity"}) {
            final String source = Files.readString(sources.resolve(name + ".java"));
            assertTrue(name, source.contains("ContentUriTransfer.prepareContent("));
            assertTrue(name, source.contains("request.run("));
            final int preparation = source.indexOf(name.equals("DesktopContentReceiverActivity")
                    ? "request = ContentUriTransfer.prepareContent(" : "request = prepare.get()");
            final int submit = source.indexOf("mImports.submit(", preparation);
            assertTrue(name, preparation >= 0 && submit > preparation);
            assertFalse(name, source.contains("ContentUriTransfer.importUri("));
        }
        final String repository = Files.readString(sources.resolve("DesktopFileRepository.java"));
        assertFalse(repository.contains("importContent("));
        assertFalse(repository.contains("importFiles("));
    }

    @Test
    public void cancelledImportsHaveAnExplicitUiResult() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        for (final String name : new String[]{"FileManagerActivity", "DesktopFolderController",
                "DesktopContentReceiverActivity"}) {
            final String source = Files.readString(sources.resolve(name + ".java"));
            assertTrue(name, source.contains("result.cancelled"));
            assertTrue(name, source.contains("R.plurals.file_import_cancelled"));
        }
        final String importer = Files.readString(sources.resolve("FileManagerImportController.java"));
        assertTrue(importer.contains("processed -> mOperations.updateImportProgress(processed, request.size())"));
    }

    @Test
    public void importsPreserveWorkResultsWhenFinalizingTheirScope() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        for (final String name : new String[]{"FileManagerImportController", "DesktopFolderController",
                "DesktopContentReceiverActivity"}) {
            final String source = Files.readString(sources.resolve(name + ".java"));
            assertTrue(name, source.contains("request.finish(completion.value, completion.failure)"));
        }
        final String dispatcher = Files.readString(sources.resolve("AndroidDesktopActionDispatcher.java"));
        assertTrue(dispatcher.contains("completion.value != null ? completion.value"));
        assertTrue(dispatcher.contains("CONTENT-GRANT-RELEASE-001"));
        assertTrue(dispatcher.contains("completion.value != null && completion.failure != null"));
    }

    @Test
    public void importedFileCommitsOnlyAfterTheProviderStreamHasClosed() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String transfer = Files.readString(sources.resolve("ContentUriTransfer.java"));
        final String copy = transfer.substring(transfer.indexOf("private static ShellFileInfo importUri("),
                transfer.indexOf("private static ShellFileInfo importTextToShellDirectory("));
        final int target = copy.indexOf("try (ShellFileCreation target =");
        final int input = copy.indexOf("try (InputStream input =");
        assertTrue(target >= 0 && input > target);
        assertTrue(copy.contains("            }\n            ContentStreamCopy.checkCancelled(cancelled);\n"
                + "            target.commit();"));
        assertFalse(copy.contains("input.close()"));
    }

    @Test
    public void wallpaperDescriptorIsOwnedBeforeMetadataPreparation() throws IOException {
        final Path sources = Path.of("src/main/java/io/github/mekhontsev/magicdesk");
        final String directory = Files.readString(sources.resolve("ShellDesktopDirectory.java"));
        final String write = directory.substring(directory.indexOf("void writeWallpaper("),
                directory.indexOf("boolean deleteWallpaper("));
        final int ownedInput = write.indexOf(
                "try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source))");
        assertTrue(ownedInput >= 0);
        assertTrue(ownedInput < write.indexOf("ensureMetadataDirectory();"));
        assertTrue(write.contains("ContentStreamCopy.copy(input, output, null, MAX_WALLPAPER_BYTES)"));
        assertFalse(directory.contains("Files.readAllBytes(mState)"));
    }

    private static void assertProtectedActivity(
            final String manifest, final String className) {
        final int activity = manifest.indexOf(
                "android:name=\"" + className + "\"");
        final int activityEnd = manifest.indexOf("/>", activity);
        final String declaration = manifest.substring(activity, activityEnd);
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));
    }
}
