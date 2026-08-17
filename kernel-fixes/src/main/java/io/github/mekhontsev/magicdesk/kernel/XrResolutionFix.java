package io.github.mekhontsev.magicdesk.kernel;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class XrResolutionFix {
    private static final String EXPECTED_KERNEL =
            "6.12.23-android16-5-gf1bdb13583da-ab13761046-4k";
    private static final String EXPECTED_DRIVER_SHA256 =
            "6658f1464f33cc09feefba77f5ed026dfeb6af8bbcd96e20b5a93a33288df577";
    private static final String EXPECTED_MODULE_SHA256 =
            "f1abf9dfece5b175801194c9a32bba08d6c1d913d16c73e4bc9db332613e043d";
    private static final String DRIVER_PATH = "/vendor_dlkm/lib/modules/msm_drm.ko";
    private static final String ROOT_MODULE_PATH =
            "/data/local/tmp/magicdesk-dp-mode-reset.ko";

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

    interface Callback {
        void onComplete(Result result);
    }

    private XrResolutionFix() {
    }

    static boolean isActive() {
        return new File("/sys/module/dp_mode_reset/parameters/disconnect_hits").canRead()
                && new File("/sys/module/dp_mode_reset/parameters/stale_override_hits")
                        .canRead();
    }

    static void activate(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result result;
                try {
                    final File module = extractModule(appContext);
                    result = runCheckedLoad(module);
                } catch (IOException | RuntimeException error) {
                    final String message = error.getMessage();
                    result = new Result(Code.FAILED,
                            message == null || message.isEmpty()
                                    ? error.getClass().getSimpleName()
                                    : message);
                }
                callback.onComplete(result);
            }
        }, "MagicDeskXrResolutionFix").start();
    }

    private static File extractModule(final Context context) throws IOException {
        final File target = new File(context.getNoBackupFilesDir(), "dp_mode_reset.ko");
        final File temporary = new File(context.getNoBackupFilesDir(),
                "dp_mode_reset.ko.tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("cannot replace temporary module");
        }

        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }

        try (InputStream input = context.getResources().openRawResource(
                R.raw.dp_mode_reset);
                FileOutputStream output = new FileOutputStream(temporary)) {
            final byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }

        final String actualHash = toHex(digest.digest());
        if (!EXPECTED_MODULE_SHA256.equals(actualHash)) {
            temporary.delete();
            throw new IOException("bundled module checksum mismatch: " + actualHash);
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("cannot replace extracted module");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("cannot publish extracted module");
        }
        return target;
    }

    private static Result runCheckedLoad(final File module) throws IOException {
        final String modulePath = shellQuote(module.getAbsolutePath());
        final String command =
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
                + "/system/bin/rm -f " + shellQuote(ROOT_MODULE_PATH) + "; "
                + "if ! /system/bin/cp " + modulePath + " "
                + shellQuote(ROOT_MODULE_PATH) + "; then "
                + "echo ERROR:copy_failed; exit 0; fi; "
                + "/system/bin/chmod 600 " + shellQuote(ROOT_MODULE_PATH) + "; "
                + "load_output=$(/system/bin/insmod " + shellQuote(ROOT_MODULE_PATH)
                + " 2>&1); load_status=$?; "
                + "/system/bin/rm -f " + shellQuote(ROOT_MODULE_PATH) + "; "
                + "if [ $load_status -ne 0 ]; then "
                + "echo ERROR:insmod_failed:$load_output; exit 0; fi; "
                + "if [ -d /sys/module/dp_mode_reset ]; then "
                + "echo ACTIVATED; else echo ERROR:module_not_visible; fi";
        return parseResult(runRootCommand(command));
    }

    private static Result parseResult(final String output) {
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
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("root command failed " + exitCode + ": "
                        + output.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", e);
        } finally {
            process.destroy();
        }
        return output.toString();
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
