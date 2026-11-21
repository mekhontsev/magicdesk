package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded real-app launch transport. Shared native/Python fixtures own all assertions. */
final class GuestFixtureRunner {
    // Encloses the shared one-hour installer checks and their cleanup budget.
    private static final int MAX_DEADLINE_SECONDS = 7200;
    private final Context context;
    private Path output;
    private JSONObject result;

    GuestFixtureRunner(Context context) { this.context = context; }

    void execute(Intent request) throws Exception {
        String id = request.getStringExtra("runId");
        String command = request.getStringExtra("command");
        int deadline = request.getIntExtra("deadlineSeconds", 60);
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid runId");
        if (!"shell".equals(request.getStringExtra("operation")) || command == null)
            throw new IllegalArgumentException("Missing fixture command");
        if (deadline < 1 || deadline > MAX_DEADLINE_SECONDS) throw new IllegalArgumentException("Invalid deadline");
        Path runs = context.getCacheDir().toPath().resolve("guest-fixtures/runs");
        Files.createDirectories(runs);
        output = Files.createDirectory(runs.resolve(id));
        JSONArray commands = new JSONArray();
        result = new JSONObject().put("runId", id).put("finished", false)
                .put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("targetSdk", context.getApplicationInfo().targetSdkVersion)
                .put("buildId", BuildConfig.SOURCE_ID)
                .put("nativeLibraryDir", context.getApplicationInfo().nativeLibraryDir)
                .put("fingerprint", android.os.Build.FINGERPRINT).put("commands", commands);
        try {
            save();
            result.put("selinux", new String(Files.readAllBytes(Path.of("/proc/self/attr/current")), StandardCharsets.UTF_8))
                    .put("status", new String(Files.readAllBytes(Path.of("/proc/self/status")), StandardCharsets.UTF_8))
                    .put("kernel", android.system.Os.uname().release);
            CommandShellEnvironment.configureLocal(context, request.getStringExtra("endpoint"));
            JSONObject record = new JSONObject().put("name", "shell").put("argv",
                    new JSONArray(new String[]{AndroidShell.path(context), "-c", command}));
            commands.put(record);
            save();
            run(command, deadline + 15, record);
            result.put("success", true);
        } catch (Exception | AssertionError error) {
            result.put("success", false).put("error", error.toString())
                    .put("trace", android.util.Log.getStackTraceString(error));
        } finally {
            result.put("finished", true);
            // Cancellation must still publish its exact receipt through NIO.
            boolean interrupted = Thread.interrupted();
            try { save(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private void run(String command, int timeout, JSONObject record) throws Exception {
        Path log = output.resolve("shell.log");
        Files.createFile(log);
        var execution = new CommandExecution(context, DesktopExecBackend.APP);
        var session = execution.session(null);
        var completion = new FutureTask<>(() -> session.execute(
                CommandExecution.invocation(execution.shell(), command, null)
                        + " > " + ShellCommandLine.quote(log.toString()) + " 2>&1"));
        var worker = new Thread(completion, "guest-fixture-command");
        long started = SystemClock.elapsedRealtime();
        boolean complete = false;
        int exitCode = -1;
        worker.start();
        try {
            // EVENT_WAIT: framed command completion; expiry cancels its owned relay.
            exitCode = completion.get(timeout, TimeUnit.SECONDS).exitCode();
            complete = true;
        } finally {
            session.close();
            // EVENT_WAIT: relay EOF releases the command reader; expiry is a fixture failure.
            worker.join(30_000);
            record.put("completed", complete).put("exitCode", exitCode)
                    .put("millis", SystemClock.elapsedRealtime() - started).put("output", tail(log));
            save();
            if (worker.isAlive()) throw new IOException("Fixture command reader did not stop");
        }
        // A command's exit code is test data, not a transport failure.
    }

    private static String tail(Path log) throws IOException {
        try (FileChannel file = FileChannel.open(log)) {
            long size = file.size();
            ByteBuffer bytes = ByteBuffer.allocate((int)Math.min(size, 16384));
            file.position(Math.max(0, size - bytes.capacity()));
            while (bytes.hasRemaining() && file.read(bytes) >= 0) { }
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }

    private void save() throws Exception {
        Path temporary = output.resolve("result.pending");
        Files.write(temporary, result.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, output.resolve("result.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
