package io.github.mekhontsev.magicdesk.kernel;

import android.content.Context;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

final class XrResolutionFix {
    private static final String EXPECTED_KERNEL =
            "6.12.23-android16-5-gf1bdb13583da-ab13761046-4k";
    private static final String EXPECTED_DRIVER_SHA256 =
            "6658f1464f33cc09feefba77f5ed026dfeb6af8bbcd96e20b5a93a33288df577";
    private static final String EXPECTED_MODULE_SHA256 =
            "f1abf9dfece5b175801194c9a32bba08d6c1d913d16c73e4bc9db332613e043d";
    private static final String DRIVER_PATH = "/vendor_dlkm/lib/modules/msm_drm.ko";
    private static final long ROOT_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_ROOT_OUTPUT_BYTES = 32 * 1024;
    private static final Activation ACTIVATION = new Activation();

    enum Code {
        ACTIVE,
        ACTIVATED,
        UNSUPPORTED_KERNEL,
        UNSUPPORTED_DRIVER,
        INVALID_MODULE,
        FAILED
    }

    static final class Result {
        final Code code;
        final String detail;

        Result(final Code code, final String detail) {
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }
    }

    static final class State {
        final boolean running;
        final Result result;

        State(final boolean running, final Result result) {
            this.running = running;
            this.result = result;
        }
    }

    /** One process-owned operation; activities observe without owning its worker. */
    static final class Activation {
        private final Set<Runnable> mObservers = new LinkedHashSet<>();
        private State mState = new State(false, null);

        synchronized State state() {
            return mState;
        }

        synchronized void observe(final Runnable observer) {
            mObservers.add(observer);
        }

        synchronized void removeObserver(final Runnable observer) {
            mObservers.remove(observer);
        }

        boolean start(final Executor executor, final Callable<Result> loader) {
            synchronized (this) {
                if (mState.running) {
                    return false;
                }
                mState = new State(true, null);
            }
            try {
                executor.execute(() -> {
                    Result result;
                    try {
                        result = loader.call();
                    } catch (Exception error) {
                        if (error instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        result = failure(error);
                    }
                    complete(result);
                });
            } catch (RuntimeException error) {
                complete(failure(error));
            }
            notifyObservers();
            return true;
        }

        private void complete(final Result result) {
            synchronized (this) {
                mState = new State(false, result);
            }
            notifyObservers();
        }

        private void notifyObservers() {
            final ArrayList<Runnable> observers;
            synchronized (this) {
                observers = new ArrayList<>(mObservers);
            }
            for (final Runnable observer : observers) {
                observer.run();
            }
        }
    }

    private XrResolutionFix() {
    }

    static boolean isActive() {
        return new File("/sys/module/dp_mode_reset/parameters/disconnect_hits").canRead()
                && new File("/sys/module/dp_mode_reset/parameters/stale_override_hits")
                        .canRead();
    }

    static State state() {
        return ACTIVATION.state();
    }

    static void observe(final Runnable observer) {
        ACTIVATION.observe(observer);
    }

    static void removeObserver(final Runnable observer) {
        ACTIVATION.removeObserver(observer);
    }

    static void activate(final Context context) {
        final Context appContext = context.getApplicationContext();
        ACTIVATION.start(
                command -> new Thread(command, "MagicDeskXrResolutionFix").start(),
                () -> {
                    final File module = extractModule(
                            appContext.getNoBackupFilesDir(),
                            appContext.getResources().openRawResource(R.raw.dp_mode_reset),
                            EXPECTED_MODULE_SHA256);
                    try {
                        return runCheckedLoad(module);
                    } finally {
                        module.delete();
                    }
                });
    }

    private static Result failure(final Exception error) {
        final String message = error.getMessage();
        return new Result(Code.FAILED,
                message == null || message.isEmpty()
                        ? error.getClass().getSimpleName() : message);
    }

    static File extractModule(final File directory, final InputStream source,
            final String expectedHash) throws IOException {
        File temporary = null;
        boolean verified = false;
        try {
            try (InputStream input = source) {
                final MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException error) {
                    throw new IOException("SHA-256 is unavailable", error);
                }
                temporary = File.createTempFile("dp_mode_reset-", ".ko", directory);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    final byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                final String actualHash = toHex(digest.digest());
                if (!expectedHash.equals(actualHash)) {
                    throw new IOException("bundled module checksum mismatch: " + actualHash);
                }
            }
            verified = true;
            return temporary;
        } finally {
            if (!verified && temporary != null) {
                temporary.delete();
            }
        }
    }

    private static Result runCheckedLoad(final File module) throws IOException {
        return parseResult(runRootCommand(loadCommand(module)));
    }

    static String loadCommand(final File module) {
        final String modulePath = shellQuote(module.getAbsolutePath());
        final String rootModulePath = shellQuote(
                "/data/local/tmp/magicdesk-" + module.getName());
        return
                "if [ -d /sys/module/dp_mode_reset ]; then "
                + "if [ -r /sys/module/dp_mode_reset/parameters/disconnect_hits ] "
                + "&& [ -r /sys/module/dp_mode_reset/parameters/stale_override_hits ]; "
                + "then echo ACTIVE; else echo ERROR:module_name_conflict; fi; exit 0; fi; "
                + "kernel=$(/system/bin/uname -r); "
                + "if [ \"$kernel\" != '" + EXPECTED_KERNEL + "' ]; then "
                + "echo UNSUPPORTED_KERNEL:$kernel; exit 0; fi; "
                + "driver_hash=$(/system/bin/sha256sum " + shellQuote(DRIVER_PATH)
                + " 2>/dev/null); driver_hash=${driver_hash%% *}; "
                + "if [ \"$driver_hash\" != '" + EXPECTED_DRIVER_SHA256 + "' ]; then "
                + "echo UNSUPPORTED_DRIVER:$driver_hash; exit 0; fi; "
                + "module_hash=$(/system/bin/sha256sum " + modulePath
                + " 2>/dev/null); module_hash=${module_hash%% *}; "
                + "if [ \"$module_hash\" != '" + EXPECTED_MODULE_SHA256 + "' ]; then "
                + "echo INVALID_MODULE:$module_hash; exit 0; fi; "
                + "trap " + shellQuote("/system/bin/rm -f " + rootModulePath)
                + " EXIT; trap 'exit 1' HUP INT TERM; "
                + "if ! /system/bin/cp " + modulePath + " "
                + rootModulePath + "; then "
                + "echo ERROR:copy_failed; exit 0; fi; "
                + "if ! /system/bin/chmod 600 " + rootModulePath + "; then "
                + "echo ERROR:chmod_failed; exit 0; fi; "
                + "load_output=$(/system/bin/insmod " + rootModulePath
                + " 2>&1); load_status=$?; "
                + "if [ $load_status -ne 0 ]; then "
                + "echo ERROR:insmod_failed:$load_output; exit 0; fi; "
                + "if [ -d /sys/module/dp_mode_reset ]; then "
                + "echo ACTIVATED; else echo ERROR:module_not_visible; fi";
    }

    static Result parseResult(final String output) {
        final String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            final String line = lines[i].trim();
            if ("ACTIVE".equals(line)) {
                return new Result(Code.ACTIVE, "");
            }
            if ("ACTIVATED".equals(line)) {
                return new Result(Code.ACTIVATED, "");
            }
            if (line.startsWith("UNSUPPORTED_KERNEL:")) {
                return new Result(Code.UNSUPPORTED_KERNEL,
                        line.substring("UNSUPPORTED_KERNEL:".length()));
            }
            if (line.startsWith("UNSUPPORTED_DRIVER:")) {
                return new Result(Code.UNSUPPORTED_DRIVER,
                        line.substring("UNSUPPORTED_DRIVER:".length()));
            }
            if (line.startsWith("INVALID_MODULE:")) {
                return new Result(Code.INVALID_MODULE,
                        line.substring("INVALID_MODULE:".length()));
            }
            if (line.startsWith("ERROR:")) {
                return new Result(Code.FAILED, line.substring("ERROR:".length()));
            }
        }
        return new Result(Code.FAILED, output.trim());
    }

    private static String runRootCommand(final String command) throws IOException {
        return readRootResult(new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start());
    }

    static String readRootResult(final Process process) throws IOException {
        try {
            final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                    process, ROOT_TIMEOUT_MILLIS, MAX_ROOT_OUTPUT_BYTES);
            if (result.truncated) {
                throw new IOException("root command output exceeded "
                        + MAX_ROOT_OUTPUT_BYTES + " bytes");
            }
            if (result.exitCode != 0) {
                throw new IOException("root command failed " + result.exitCode + ": "
                        + result.output.trim());
            }
            return result.output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", e);
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String toHex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (final byte item : value) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }
}
