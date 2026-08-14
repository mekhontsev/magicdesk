package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;


import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.system.Os;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Exercises narrowly scoped Nubia vendor APIs from MagicDesk's real app UID.
 *
 * <p>This instrumentation is debug-only. Mutating tests must always restore the
 * original value before returning.
 */
public final class NubiaVendorProbeInstrumentation extends Instrumentation {
    private static final String DESKTOP_RESTRICTIONS_PROPERTY =
            "persist.wm.debug.desktop_mode_enforce_device_restrictions";
    private static final String REDMAGIC_SERVICE = "redmagic.app.manager";
    private static final String DISPLAY_GET_MIRROR_TYPE_TRANSACTION = "52";
    private static final String SCENE_GET_TEMPERATURE_TRANSACTION = "38";
    private static final String SCENE_GET_FOREGROUND_TRANSACTION = "39";
    private static final String SCENE_GET_VISIBLE_TRANSACTION = "41";
    private static final String SCENE_GET_SMALL_WINDOWS_TRANSACTION = "42";
    private static final String SCENE_GET_CURRENT_TRANSACTION = "62";
    private static final String BACKLIGHT_GET_NIT_TRANSACTION = "3";
    private static final String BACKLIGHT_GET_LEVEL_TRANSACTION = "4";
    private static final ComponentName MIRROR_INPUT_SERVICE =
            new ComponentName(
                    "cn.nubia.keymapcenter",
                    "cn.nubia.keymapcenter.mirror.MirrorInputService");
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;

    private boolean mAllowMutation;

    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        mAllowMutation = arguments != null
                && "true".equals(arguments.getString("allow_mutation"));
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(this::runProbe, "MagicDeskNubiaVendorProbe");
        thread.setDaemon(true);
        thread.start();
    }

    private void runProbe() {
        final Bundle result = new Bundle();
        boolean success = true;
        result.putString("identity", identity());

        try {
            result.putString("property_read", probePropertyRead());
        } catch (IOException | InterruptedException error) {
            success = false;
            result.putString("property_read", failure(error));
        }

        if (mAllowMutation) {
            try {
                result.putString("property_mutation", probePropertyMutation());
            } catch (IOException | InterruptedException error) {
                success = false;
                result.putString("property_mutation", failure(error));
            }
            try {
                result.putString(
                        "caption_visibility_mutation",
                        probeCaptionVisibilityMutation());
            } catch (IOException error) {
                success = false;
                result.putString(
                        "caption_visibility_mutation",
                        failure(error));
            }
        } else {
            result.putString("property_mutation", "skipped");
            result.putString("caption_visibility_mutation", "skipped");
        }

        try {
            result.putString("display_read", probeDisplayRead());
        } catch (IOException | InterruptedException error) {
            success = false;
            result.putString("display_read", failure(error));
        }

        try {
            result.putString("scene_state", probeSceneState());
        } catch (IOException | InterruptedException error) {
            success = false;
            result.putString("scene_state", failure(error));
        }

        try {
            result.putString("scene_callback", probeSceneCallback());
        } catch (IOException | InterruptedException error) {
            success = false;
            result.putString("scene_callback", failure(error));
        }

        try {
            result.putString("backlight_state", probeBacklightState());
        } catch (IOException | InterruptedException error) {
            success = false;
            result.putString("backlight_state", failure(error));
        }

        result.putString("global_setting_write", probeGlobalSettingWrite());

        try {
            result.putString(
                    "mirror_input_service",
                    probeMirrorInputServiceMetadata());
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            success = false;
            result.putString("mirror_input_service", failure(error));
        }

        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static String identity() {
        String selinux = "unavailable";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream("/proc/self/attr/current"),
                        StandardCharsets.UTF_8))) {
            final String value = reader.readLine();
            if (value != null && !value.isBlank()) {
                selinux = value.trim();
            }
        } catch (IOException ignored) {
            // UID still distinguishes this process from the Shizuku service.
        }
        return "uid=" + Os.getuid() + " gid=" + Os.getgid()
                + " selinux=" + selinux;
    }

    private static String probePropertyRead()
            throws IOException, InterruptedException {
        final CommandResult property = command(
                "/system/bin/getprop",
                DESKTOP_RESTRICTIONS_PROPERTY);
        final CommandResult binder = command(
                "/system/bin/service",
                "call",
                REDMAGIC_SERVICE,
                "3",
                "s16",
                DESKTOP_RESTRICTIONS_PROPERTY,
                "s16",
                "unavailable");
        return "value=" + property.requireSuccess().trim()
                + " binder_exit=" + binder.exitCode
                + " binder_reply=" + oneLine(binder.output);
    }

    private static String probePropertyMutation()
            throws IOException, InterruptedException {
        final NubiaDesktopPropertyManager.Property property =
                NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS;
        final String original = NubiaDesktopPropertyManager.read(property);
        if (!"true".equals(original) && !"false".equals(original)) {
            throw new IOException("unexpected original value: " + original);
        }
        final String temporary = "true".equals(original) ? "false" : "true";
        String observed = "";
        IOException failure = null;
        try {
            NubiaDesktopPropertyManager.write(property, temporary);
            observed = NubiaDesktopPropertyManager.read(property);
            if (!temporary.equals(observed)) {
                throw new IOException(
                        "write did not take effect: expected=" + temporary
                                + " observed=" + observed);
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            try {
                NubiaDesktopPropertyManager.write(property, original);
                final String restored =
                        NubiaDesktopPropertyManager.read(property);
                if (!original.equals(restored)) {
                    throw new IOException(
                            "restore failed: expected=" + original
                                    + " observed=" + restored);
                }
            } catch (IOException restoreError) {
                if (failure != null) {
                    restoreError.addSuppressed(failure);
                }
                throw restoreError;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return "changed=" + original + "->" + observed
                + " restored=" + original;
    }

    private static String probeCaptionVisibilityMutation()
            throws IOException {
        if (!NubiaCaptionVisibilityManager.setTransport(
                NubiaCaptionVisibilityManager.Transport.WIRELESS)) {
            throw new IOException("could not enable wireless captions");
        }
        if (!NubiaCaptionVisibilityManager.setTransport(
                NubiaCaptionVisibilityManager.Transport.WIRED)) {
            throw new IOException(
                    "could not restore wireless privacy or enable wired captions");
        }
        if (!NubiaCaptionVisibilityManager.setTransport(
                NubiaCaptionVisibilityManager.Transport.NONE)) {
            throw new IOException("could not restore wired privacy");
        }
        return "changed=wireless,wired restored=vendor-privacy";
    }

    private static String probeDisplayRead()
            throws IOException, InterruptedException {
        final CommandResult result = command(
                "/system/bin/service",
                "call",
                "display",
                DISPLAY_GET_MIRROR_TYPE_TRANSACTION,
                "i32",
                "0");
        return "exit=" + result.exitCode
                + " reply=" + oneLine(result.output);
    }

    private static String probeSceneState()
            throws IOException, InterruptedException {
        final CommandResult temperature = command(
                "/system/bin/service",
                "call",
                "scenedecision",
                SCENE_GET_TEMPERATURE_TRANSACTION);
        final CommandResult scene = command(
                "/system/bin/service",
                "call",
                "scenedecision",
                SCENE_GET_CURRENT_TRANSACTION);
        final CommandResult foreground = command(
                "/system/bin/service",
                "call",
                "scenedecision",
                SCENE_GET_FOREGROUND_TRANSACTION);
        final CommandResult visible = command(
                "/system/bin/service",
                "call",
                "scenedecision",
                SCENE_GET_VISIBLE_TRANSACTION);
        final CommandResult smallWindows = command(
                "/system/bin/service",
                "call",
                "scenedecision",
                SCENE_GET_SMALL_WINDOWS_TRANSACTION);
        return "temperature_exit=" + temperature.exitCode
                + " temperature_reply=" + oneLine(temperature.output)
                + " scene_exit=" + scene.exitCode
                + " scene_reply=" + oneLine(scene.output)
                + " foreground_exit=" + foreground.exitCode
                + " foreground_reply=" + oneLine(foreground.output)
                + " visible_exit=" + visible.exitCode
                + " visible_reply=" + oneLine(visible.output)
                + " small_windows_exit=" + smallWindows.exitCode
                + " small_windows_reply=" + oneLine(smallWindows.output);
    }

    private static String probeBacklightState()
            throws IOException, InterruptedException {
        final CommandResult nit = command(
                "/system/bin/service",
                "call",
                "zte_backlight",
                BACKLIGHT_GET_NIT_TRANSACTION);
        final CommandResult backlight = command(
                "/system/bin/service",
                "call",
                "zte_backlight",
                BACKLIGHT_GET_LEVEL_TRANSACTION);
        return "nit_exit=" + nit.exitCode
                + " nit_reply=" + oneLine(nit.output)
                + " backlight_exit=" + backlight.exitCode
                + " backlight_reply=" + oneLine(backlight.output);
    }

    private String probeSceneCallback()
            throws IOException, InterruptedException {
        final String apk = getTargetContext().getApplicationInfo().sourceDir;
        final ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/app_process",
                "/",
                "io.github.mekhontsev.magicdesk.platform.nubia."
                        + "NubiaSceneCallbackProbeCommand");
        builder.environment().put("CLASSPATH", apk);
        final CommandResult result = run(builder);
        return "exit=" + result.exitCode
                + " output=" + oneLine(result.output);
    }

    private String probeGlobalSettingWrite() {
        final Context context = getTargetContext().getApplicationContext();
        final String key = "enable_freeform_support";
        final String original = Settings.Global.getString(
                context.getContentResolver(), key);
        try {
            final boolean changed = Settings.Global.putString(
                    context.getContentResolver(), key, original);
            return "unexpectedly_allowed=" + changed;
        } catch (SecurityException error) {
            return "denied=" + oneLine(String.valueOf(error.getMessage()));
        }
    }

    private String probeMirrorInputServiceMetadata()
            throws PackageManager.NameNotFoundException {
        final ServiceInfo info = getTargetContext()
                .getPackageManager()
                .getServiceInfo(MIRROR_INPUT_SERVICE, 0);
        return "exported=" + info.exported
                + " enabled=" + info.enabled
                + " permission=" + String.valueOf(info.permission);
    }

    private static CommandResult command(final String... command)
            throws IOException, InterruptedException {
        return run(new ProcessBuilder(command));
    }

    private static CommandResult run(final ProcessBuilder builder)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                builder.start(),
                COMMAND_TIMEOUT_MILLIS,
                MAX_OUTPUT_BYTES);
        return new CommandResult(result.exitCode, result.output);
    }

    private static String oneLine(final String value) {
        return value.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private static String failure(final Throwable error) {
        final String message = error.getMessage();
        return "failed: " + error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(final int exitCode, final String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        String requireSuccess() throws IOException {
            if (exitCode != 0) {
                throw new IOException(
                        "command exited " + exitCode + ": " + oneLine(output));
            }
            return output;
        }
    }
}
