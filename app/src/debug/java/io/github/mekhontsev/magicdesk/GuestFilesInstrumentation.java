package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.hosted.HostedFileExchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Actual guest filesystem/UID boundary, independent of Desktop and a graphics protocol. */
public final class GuestFilesInstrumentation extends Instrumentation {
    private String store;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        store = arguments.getString("store");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            exercise();
            result.putString("guest_files", "PASS authenticated helper, import, independent guest read, descriptor export, cleanup");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("guest_files", "FAIL " + failure);
            result.putString("trace", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void exercise() throws Exception {
        if (ShellAccess.currentSnapshot().uid != 2000) throw new IllegalArgumentException("UID 2000 required");
        var context = getTargetContext();
        var identity = new GuestEnvironment(store, "", "root", true);
        var setup = new ShellCommandSession("/data/local/tmp");
        var worker = new ShellCommandSession("/data/local/tmp");
        var completed = new CompletableFuture<ShellCommandOutput.Result>();
        var ready = new CompletableFuture<Void>();
        String endpoint = "magicdesk-file-fixture-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        var imported = context.getCacheDir().toPath().resolve("guest-files-" + UUID.randomUUID());
        byte[] body = "Android to guest and back\n".getBytes(StandardCharsets.UTF_8);
        Files.write(imported, body);
        HostedFileExchange exchange = null;
        ExecutorSocketEndpoint admission = null;
        boolean started = false;
        try {
            var prepare = new GuestLaunchPlan(identity, "", List.of("/bin/sh", "-c", "mkdir -p /tmp/magicdesk-helpers"));
            require(setup.execute(words(prepare.arguments())).exitCode() == 0, "helper directory");
            exchange = new HostedFileExchange(context.getCacheDir().getPath() + "/unused-files", null, "", token);
            var target = exchange;
            admission = new ExecutorSocketEndpoint(2000, 10_000, fd -> target.acceptGuest(ParcelFileDescriptor.dup(fd.getFileDescriptor())),
                    ready::completeExceptionally);
            endpoint = admission.name;
            var plan = new GuestLaunchPlan(identity, "", List.of("/tmp/magicdesk-helpers/libmagicdesk_guest_files.so", "--",
                    "/bin/sh", "-c", "printf READY; exec tail -f /dev/null"));
            String command = words(plan.launcherArguments()) + " --bind-ro " + ShellCommandLine.quote(context.getApplicationInfo().nativeLibraryDir)
                    + " /tmp/magicdesk-helpers --env " + ShellCommandLine.quote("MAGICDESK_GUEST_FILES_SOCKET=" + endpoint)
                    + " --env " + ShellCommandLine.quote("MAGICDESK_GUEST_FILES_TOKEN=" + token)
                    + " -- " + words(plan.command());
            new Thread(() -> {
                try {
                    StringBuilder output = new StringBuilder();
                    var result = worker.execute(command, (bytes, offset, count) -> {
                        output.append(new String(bytes, offset, count, StandardCharsets.UTF_8));
                        if (output.toString().contains("READY")) ready.complete(null);
                    });
                    completed.complete(result);
                    if (!ready.isDone()) ready.completeExceptionally(new AssertionError("Helper exited before admission: " + result + " " + output));
                } catch (Throwable error) { completed.completeExceptionally(error); ready.completeExceptionally(error); }
            }, "GuestFileFixture").start();
            started = true;
            // EVENT_WAIT: helper authenticates before its wrapped command prints READY; expiry fails admission.
            ready.get(30, TimeUnit.SECONDS);
            String uri = exchange.importFile(ParcelFileDescriptor.open(imported.toFile(), ParcelFileDescriptor.MODE_READ_ONLY), "original name.txt");
            String path = java.net.URI.create(uri).getPath();
            require(path.endsWith("/original name.txt"), "original filename");
            var read = new GuestLaunchPlan(identity, "", List.of("/bin/cat", path));
            var independent = setup.execute(words(read.arguments()));
            require(independent.exitCode() == 0 && independent.output().equals(new String(body, StandardCharsets.UTF_8)), "independent guest read");
            try (var input = new ParcelFileDescriptor.AutoCloseInputStream(exchange.open(uri))) {
                require(java.util.Arrays.equals(body, input.readAllBytes()), "descriptor export");
            }
            exchange.close();
            var cleanup = new GuestLaunchPlan(identity, "", List.of("/bin/sh", "-c", "test ! -e " + ShellCommandLine.quote(path)));
            // BOUNDED_STATE_WAIT: helper cleanup has no callback; each fresh guest command observes the file, never assumes expiry means success.
            var removed = BoundedStateAwaiter.awaitIo(BoundedStateAwaiter.Reason.RUNTIME_READY,
                    10_000, 100, () -> setup.execute(words(cleanup.arguments())), value -> value.exitCode() == 0);
            require(removed.exitCode() == 0, "helper cleanup");
        } finally {
            if (exchange != null) exchange.close();
            if (admission != null) admission.close();
            worker.close(); setup.close(); Files.deleteIfExists(imported);
            if (started) {
                // EVENT_WAIT: owned command stream closes after cancellation; expiry fails fixture cleanup.
                try { completed.get(30, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expected) { }
            }
        }
    }

    private static String words(List<String> values) { return String.join(" ", values.stream().map(ShellCommandLine::quote).toList()); }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
