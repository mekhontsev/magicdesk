package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import io.github.mekhontsev.magicdesk.hosted.GuestFileBridge;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** PTY-owned copies. Android windows only subscribe; provider grants belong to the import. */
final class ConsoleSessionFiles implements Closeable {
    enum Source { FILES, DIRECTORY, CAMERA }
    record Item(String key, String name, String path, long bytes) { }
    record State(List<Item> items, boolean busy, boolean closed, String progress, String error) { }
    private static final long MAX_FILE = 128L * 1024 * 1024, MAX_TOTAL = 256L * 1024 * 1024;
    private final Context context;
    private final DesktopExecBackend backend;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "TerminalFiles"));
    private final ContentRequestScope requests = new ContentRequestScope(worker);
    private final java.util.Set<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final java.util.concurrent.atomic.AtomicBoolean notificationPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<Item> items = new ArrayList<>();
    private volatile boolean closed, cancelled;
    private boolean busy;
    private String progress = "", error = "", picker = "", directory = "";
    private GuestFileBridge bridge;
    private Closeable admission, command;
    private GeneratedContentProvider.Capture capture;
    private final java.util.concurrent.atomic.AtomicBoolean preparing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Map<String, String>> prepared = new java.util.concurrent.CompletableFuture<>();

    ConsoleSessionFiles(Context context, DesktopExecBackend backend) {
        this.context = context.getApplicationContext(); this.backend = backend;
    }

    Map<String, String> prepare() {
        if (preparing.compareAndSet(false, true)) prepared.complete(prepareChannel());
        // EVENT_WAIT: another client of this tmux session preparing the shared file channel.
        try { return prepared.get(20, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return Map.of(); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            failed(failure); return Map.of();
        }
    }

    private Map<String, String> prepareChannel() {
        try {
            var execution = new CommandExecution(context, backend);
            byte[] secret = new byte[32]; new java.security.SecureRandom().nextBytes(secret);
            String token = java.util.HexFormat.of().formatHex(secret);
            GuestFileBridge channel = backend == DesktopExecBackend.TERMUX
                    ? GuestFileBridge.loopback(token) : new GuestFileBridge("", token);
            String endpoint;
            synchronized (this) {
                if (closed) { channel.close(); return Map.of(); }
                bridge = channel;
                if (backend != DesktopExecBackend.TERMUX) {
                    var socket = new ExecutorSocketEndpoint(execution.uid, 10_000,
                            fd -> channel.offer(ParcelFileDescriptor.dup(fd.getFileDescriptor())), failure -> channel.close());
                    admission = socket; endpoint = socket.name;
                } else endpoint = channel.endpoint();
            }
            String helper = context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_guest_files.so";
            String launch = "export TMPDIR="
                    + ShellCommandLine.quote(backend == DesktopExecBackend.TERMUX ? execution.home + "/../usr/tmp" : "/data/local/tmp")
                    + " MAGICDESK_GUEST_FILES_SOCKET=" + ShellCommandLine.quote(endpoint)
                    + " MAGICDESK_GUEST_FILES_TOKEN=" + ShellCommandLine.quote(token)
                    + "\nexec " + ShellCommandLine.quote(helper) + " --serve";
            synchronized (this) {
                if (closed) return Map.of();
                command = execution.start(launch, execution.home, "Terminal files", null,
                        (status, output, failure) -> {
                            synchronized (this) {
                                directory = ""; items.clear();
                                if (!closed) failed(failure == null ? new IOException("File helper stopped (" + status + ")"
                                        + (output.isBlank() ? "" : ": " + output.strip().substring(0, Math.min(output.strip().length(), 512)))) : failure);
                            }
                            channel.close();
                        });
            }
            channel.awaitReady();
            String root = channel.directory();
            synchronized (this) {
                if (closed) return Map.of();
                directory = root;
                return Map.of(TerminalFilesEnvironment.DIRECTORY, root);
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) { if (error.isEmpty()) failed(failure); }
            releaseChannel();
            return Map.of(); // Attachments must never prevent an independent terminal from opening.
        }
    }

    synchronized State state() { return new State(List.copyOf(items), busy, closed, progress, error); }
    synchronized String shellPaths(List<String> paths) {
        if (closed || directory.isEmpty()) return "";
        return items.stream().filter(item -> paths.contains(item.path()))
                .map(item -> TerminalFilesEnvironment.quoteRelativePath(item.key() + "/" + item.name()))
                .collect(java.util.stream.Collectors.joining(" "));
    }
    void subscribe(Runnable listener) { listeners.add(listener); }
    void unsubscribe(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        if (notificationPending.compareAndSet(false, true)) main.post(() -> {
            notificationPending.set(false);
            for (Runnable listener : listeners) listener.run();
            if (closed) listeners.clear();
        });
    }
    private synchronized void failed(Throwable failure) { error = ShellAccess.usefulMessage(failure); changed(); }
    private synchronized boolean begin() {
        if (closed || busy) return false;
        if (directory.isEmpty()) {
            if (error.isEmpty()) error = "File channel is unavailable";
            changed(); return false;
        }
        busy = true; cancelled = false; error = ""; progress = ""; changed(); return true;
    }
    private synchronized void finish(Throwable failure) {
        busy = false; progress = "";
        if (failure != null && !cancelled && !closed) error = ShellAccess.usefulMessage(failure);
        changed();
    }

    void choose(Source source, int display) {
        if (!begin()) return;
        requests.submit(stopped -> {
            Intent intent;
            GeneratedContentProvider.Capture photo = null;
            if (source == Source.CAMERA) {
                photo = GeneratedContentProvider.capture(context);
                synchronized (this) {
                    if (closed || cancelled) { photo.close(); return null; }
                    capture = photo;
                }
                intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photo.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Camera output", photo.uri));
            } else {
                intent = new Intent(source == Source.DIRECTORY ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if (source == Source.FILES) intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            ContentStreamCopy.checkCancelled(() -> closed || cancelled);
            var request = AndroidIntegrationRequest.activity(intent, "Terminal files",
                    DesktopLaunchPresentation.automatic().withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), false, "", true);
            var launched = new AndroidIntegrationGateway(context).execute(
                    AndroidDesktopAction.request("terminal-files", "console", request), display);
            String id = (launched.success ? launched.data : launched.observation).optString("requestId", "");
            if (id.isEmpty()) throw new IOException(launched.message);
            synchronized (this) {
                if (closed || cancelled) { AndroidActivityResultStore.discard(id); return null; }
                picker = id;
            }
            GeneratedContentProvider.Capture output = photo;
            AndroidActivityResultStore.whenReady(id, () -> selected(id, source, output));
            return id;
        }, null).thenAccept(result -> {
            if (result.failure != null || result.value == null) { closeCapture(); finish(result.failure); }
        });
    }

    private void selected(String id, Source source, GeneratedContentProvider.Capture photo) {
        var result = AndroidActivityResultStore.claim(id);
        synchronized (this) { if (picker.equals(id)) picker = ""; }
        requests.submit(stopped -> {
            if (result == null) return null;
            var value = result.toJson();
            if (!"completed".equals(value.optString("state"))) throw new IOException(value.optString("error", "Selection failed"));
            if (value.optInt("resultCode") != Activity.RESULT_OK) return null;
            var uris = new java.util.LinkedHashSet<Uri>();
            if (photo != null) uris.add(photo.uri);
            else {
                var data = value.getJSONObject("data");
                String uri = data.optString("dataUri", "");
                if (!uri.isEmpty()) uris.add(Uri.parse(uri));
                var clips = data.optJSONArray("clipUris");
                if (clips != null) for (int i = 0; i < clips.length(); i++) uris.add(Uri.parse(clips.getString(i)));
                if (data.optBoolean("clipUrisTruncated")) throw new IOException("Too many selected files");
            }
            for (Uri uri : uris) importOne(uri, source == Source.DIRECTORY);
            return null;
        }, () -> { if (result != null) result.close(); if (photo != null) photo.close(); })
                .thenAccept(done -> { synchronized (this) { if (capture == photo) capture = null; } finish(done.failure); });
    }

    void importUris(List<Uri> uris) {
        if (!begin()) return;
        requests.submit(stopped -> { for (Uri uri : uris) importOne(uri, false); return null; }, null)
                .thenAccept(done -> finish(done.failure));
    }

    private void importOne(Uri uri, boolean tree) throws Exception {
        ContentStreamCopy.checkCancelled(() -> closed || cancelled);
        long available;
        synchronized (this) {
            if (items.size() >= 128) throw new IOException("Too many session attachments");
            available = MAX_TOTAL - items.stream().mapToLong(Item::bytes).sum();
        }
        String key = UUID.randomUUID().toString();
        if (tree) uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        String name = safeName(ContentUriTransfer.displayName(context.getContentResolver(), uri, "content"));
        String relative = key + "/" + name;
        bridge.mkdir(key);
        long[] copied = {0, 0};
        boolean committed = false;
        try {
            copy(uri, relative, tree, 0, available, copied);
            ContentStreamCopy.checkCancelled(() -> closed || cancelled);
            synchronized (this) {
                if (closed || directory.isEmpty()) throw new IOException("File channel closed");
                items.add(new Item(key, name, directory + "/" + relative, copied[0]));
                committed = true;
            }
            changed();
        } finally { if (!committed) bridge.remove(key); }
    }

    private void copy(Uri uri, String relative, boolean tree, int depth, long available, long[] copied) throws Exception {
        ContentStreamCopy.checkCancelled(() -> closed || cancelled);
        if (depth > 32 || ++copied[1] > 4096) throw new IOException("Directory is too large or deeply nested");
        synchronized (this) { progress = relative.substring(relative.indexOf('/') + 1); }
        changed();
        if (tree && DocumentsContract.Document.MIME_TYPE_DIR.equals(context.getContentResolver().getType(uri))) {
            bridge.mkdir(relative);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri));
            try (var cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) throw new IOException("Directory cannot be read");
                while (cursor.moveToNext()) copy(DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)),
                        relative + "/" + safeName(cursor.getString(1)), true, depth + 1, available, copied);
            }
        } else {
            try (var file = ContentUriTransfer.materialize(context, uri, () -> closed || cancelled,
                    Math.min(MAX_FILE, available - copied[0]))) {
                bridge.put(relative, file);
                copied[0] += file.getStatSize();
            }
        }
    }

    static String safeName(String name) {
        ShellFileNamePolicy.validate(name);
        String value = name;
        if (value.indexOf('\\') >= 0) throw new IllegalArgumentException("Invalid file name");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Control character in file name");
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240)
            throw new IllegalArgumentException("File name is too long");
        return value;
    }

    void remove(List<Item> selected) {
        if (!begin()) return;
        requests.submit(stopped -> {
            for (Item item : selected) {
                ContentStreamCopy.checkCancelled(() -> closed || cancelled);
                synchronized (this) { if (!items.contains(item)) continue; }
                bridge.remove(item.key());
                synchronized (this) { items.remove(item); }
                changed();
            }
            return null;
        }, null).thenAccept(done -> finish(done.failure));
    }

    void cancel() {
        String pending;
        synchronized (this) { cancelled = true; pending = picker; picker = ""; }
        if (!pending.isEmpty()) AndroidActivityResultStore.discard(pending);
    }
    private synchronized void closeCapture() { if (capture != null) { capture.close(); capture = null; } }
    private synchronized void releaseChannel() {
        if (bridge != null) bridge.close();
        if (admission != null) try { admission.close(); } catch (IOException ignored) { }
        // Connected helpers exit on EOF and clean up before their command transport closes.
        if (command != null && directory.isEmpty()) try { command.close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        prepared.complete(Map.of());
        cancel(); requests.close(); releaseChannel(); closeCapture();
        worker.shutdown(); changed();
    }
}
