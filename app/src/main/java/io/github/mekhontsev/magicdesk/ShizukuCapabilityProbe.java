package io.github.mekhontsev.magicdesk;

import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputMonitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

final class ShizukuCapabilityProbe {
    private static final String[] PERMISSIONS = {
            "android.permission.INJECT_EVENTS",
            "android.permission.MONITOR_INPUT",
            "android.permission.SET_KEYBOARD_LAYOUT",
            "android.permission.REMAP_MODIFIER_KEYS",
            "android.permission.MANAGE_KEY_GESTURES",
            "android.permission.LISTEN_FOR_KEY_ACTIVITY",
            "android.permission.MANAGE_ACTIVITY_TASKS",
            "android.permission.INTERNAL_SYSTEM_WINDOW",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.DEVICE_POWER",
            "android.permission.STATUS_BAR",
            "android.permission.SET_ORIENTATION",
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.READ_FRAME_BUFFER",
            "android.permission.REBOOT",
            "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
    };

    private static final String[] REDMAGIC_HARDWARE_NODES = {
            "/sys/kernel/fan/fan_enable",
            "/sys/kernel/fan/fan_speed_level",
            "/sys/kernel/fan/fan_speed_count",
            "/proc/driver/micropump/enable",
            "/proc/driver/micropump/freq",
            "/proc/driver/micropump/speed"
    };

    private ShizukuCapabilityProbe() {
    }

    static String run(final Context context) {
        final StringBuilder report = new StringBuilder(3_000);
        report.append("format=1\n")
                .append("identity.uid=").append(Os.getuid()).append('\n')
                .append("identity.gid=").append(Os.getgid()).append('\n')
                .append("identity.groups=").append(readStatusValue("Groups")).append('\n')
                .append("identity.capabilities=")
                .append(readStatusValue("CapEff")).append('\n')
                .append("identity.selinux=")
                .append(readFirstLine("/proc/self/attr/current")).append('\n');

        appendPermissions(report, context);
        appendRawInput(report);
        appendOpenResult(
                report,
                "input.uinput",
                new File("/dev/uinput"),
                OsConstants.O_WRONLY);
        appendFileAccess(
                report,
                "input.state",
                new File("/data/system/input-manager-state.xml"));
        appendInputControlAccess(report);
        appendInputMonitor(report);
        appendTaskAccess(report);
        appendMethodPresence(
                report,
                "vendor.display_command",
                "android.hardware.display.IDisplayManager",
                "setCmdToDisplay",
                int.class,
                int.class,
                int.class,
                Bundle.class);
        appendMethodPresence(
                report,
                "vendor.phone_screen",
                "com.redmagic.os.RedMagicAppManager$Trigger",
                "openScreenOffTP",
                boolean.class);
        appendService(report, "vendor.color_light", "ColorfulLightService");
        appendService(report, "vendor.power", "VendorPowerManagerService");
        appendHardwareNodes(report);
        return report.toString();
    }

    private static void appendPermissions(
            final StringBuilder report,
            final Context context) {
        for (final String permission : PERMISSIONS) {
            final String key = permission.substring(
                    permission.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);
            if (context == null) {
                append(report, "permission." + key, "unknown", "no service context");
                continue;
            }
            final boolean granted = context.checkPermission(
                    permission,
                    Process.myPid(),
                    Process.myUid()) == PackageManager.PERMISSION_GRANTED;
            append(report, "permission." + key, granted ? "granted" : "denied", "");
        }
    }

    private static void appendRawInput(final StringBuilder report) {
        final File directory = new File("/dev/input");
        final File[] devices = directory.listFiles(
                (parent, name) -> name.startsWith("event"));
        if (devices == null || devices.length == 0) {
            append(report, "raw_input.read", "unavailable", "no event devices");
            append(report, "raw_input.write", "unavailable", "no event devices");
            return;
        }
        Arrays.sort(devices, Comparator.comparing(File::getName));
        final File device = devices[0];
        appendOpenResult(report, "raw_input.read", device, OsConstants.O_RDONLY);
        appendOpenResult(report, "raw_input.write", device, OsConstants.O_RDWR);
    }

    private static void appendOpenResult(
            final StringBuilder report,
            final String key,
            final File file,
            final int mode) {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(
                    file.getAbsolutePath(),
                    mode | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC,
                    0);
            append(report, key, "granted", file.getAbsolutePath());
        } catch (ErrnoException error) {
            append(report, key, "denied", usefulMessage(error));
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                    // The probe never retains an input descriptor.
                }
            }
        }
    }

    private static void appendInputMonitor(final StringBuilder report) {
        InputMonitor monitor = null;
        try {
            final Object inputManager = getServiceInterface(
                    "input", "android.hardware.input.IInputManager");
            final Class<?> inputManagerInterface =
                    Class.forName("android.hardware.input.IInputManager");
            final Method method = inputManagerInterface.getMethod(
                    "monitorGestureInput",
                    IBinder.class,
                    String.class,
                    int.class);
            monitor = (InputMonitor) method.invoke(
                    inputManager,
                    new Binder(),
                    "MagicDesk capability probe",
                    Integer.valueOf(0));
            append(report, "input.monitor", "granted", "display=0");
        } catch (Throwable error) {
            append(report, "input.monitor", "denied", usefulMessage(error));
        } finally {
            if (monitor != null) {
                try {
                    monitor.dispose();
                } catch (RuntimeException ignored) {
                    // Cleanup failure must not discard the capability report.
                }
            }
        }
    }

    private static void appendInputControlAccess(final StringBuilder report) {
        try {
            final Object inputManager = getServiceInterface(
                    "input", "android.hardware.input.IInputManager");
            final Class<?> inputManagerInterface =
                    Class.forName("android.hardware.input.IInputManager");

            appendPermissionValidatedCall(
                    report,
                    "input.inject",
                    inputManager,
                    inputManagerInterface.getMethod(
                            "injectInputEvent",
                            Class.forName("android.view.InputEvent"),
                            int.class),
                    null,
                    Integer.valueOf(0));
            appendPermissionValidatedCall(
                    report,
                    "input.layout_write",
                    inputManager,
                    inputManagerInterface.getMethod(
                            "setKeyboardLayoutForInputDevice",
                            Class.forName(
                                    "android.hardware.input.InputDeviceIdentifier"),
                            int.class,
                            Class.forName(
                                    "android.view.inputmethod.InputMethodInfo"),
                            Class.forName(
                                    "android.view.inputmethod.InputMethodSubtype"),
                            String.class),
                    null,
                    Integer.valueOf(0),
                    null,
                    null,
                    null);

            final Method modifierMappings =
                    inputManagerInterface.getMethod("getModifierKeyRemapping");
            final Object mappings = modifierMappings.invoke(inputManager);
            append(
                    report,
                    "input.modifier_remap_read",
                    "granted",
                    mappings == null ? "null" : mappings.getClass().getSimpleName());
        } catch (Throwable error) {
            append(
                    report,
                    "input.control_probe",
                    "error",
                    usefulMessage(error));
        }
    }

    private static void appendPermissionValidatedCall(
            final StringBuilder report,
            final String key,
            final Object target,
            final Method method,
            final Object... arguments) {
        try {
            method.invoke(target, arguments);
            append(report, key, "granted", "invalid argument accepted");
        } catch (InvocationTargetException error) {
            final Throwable cause = unwrap(error);
            if (cause instanceof SecurityException) {
                append(report, key, "denied", usefulMessage(cause));
            } else if (cause instanceof NullPointerException
                    || cause instanceof IllegalArgumentException) {
                append(report, key, "granted", "permission check passed");
            } else {
                append(report, key, "error", usefulMessage(cause));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            append(report, key, "error", usefulMessage(error));
        }
    }

    private static void appendTaskAccess(final StringBuilder report) {
        final Object service;
        try {
            service = HiddenTaskApi.getService();
            final int taskCount = HiddenTaskApi.getTasks(service, 0, 8).size();
            append(report, "tasks.read", "granted", "display0_count=" + taskCount);
        } catch (Throwable error) {
            append(report, "tasks.read", "denied", usefulMessage(error));
            append(report, "tasks.listener", "not_tested", "task service unavailable");
            return;
        }

        TaskStackListener listener = null;
        Method unregister = null;
        try {
            final Class<?> listenerClass =
                    Class.forName("android.app.ITaskStackListener");
            final Method register = service.getClass().getMethod(
                    "registerTaskStackListener", listenerClass);
            unregister = service.getClass().getMethod(
                    "unregisterTaskStackListener", listenerClass);
            listener = new TaskStackListener() {
            };
            register.invoke(service, listener);
            append(report, "tasks.listener", "granted", "");
        } catch (Throwable error) {
            append(report, "tasks.listener", "denied", usefulMessage(error));
        } finally {
            if (service != null && listener != null && unregister != null) {
                try {
                    unregister.invoke(service, listener);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A transient listener is harmless if the process exits first.
                }
            }
        }
    }

    private static void appendMethodPresence(
            final StringBuilder report,
            final String key,
            final String className,
            final String methodName,
            final Class<?>... parameterTypes) {
        try {
            Class.forName(className).getMethod(methodName, parameterTypes);
            append(report, key, "present", className + "#" + methodName);
        } catch (ReflectiveOperationException | RuntimeException error) {
            append(report, key, "missing", usefulMessage(error));
        }
    }

    private static void appendService(
            final StringBuilder report,
            final String key,
            final String serviceName) {
        try {
            final IBinder binder = getServiceBinder(serviceName);
            if (binder == null) {
                append(report, key, "missing", serviceName);
                return;
            }
            append(report, key, "present", binder.getInterfaceDescriptor());
        } catch (Throwable error) {
            append(report, key, "error", usefulMessage(error));
        }
    }

    private static void appendHardwareNodes(final StringBuilder report) {
        int present = 0;
        boolean readable = false;
        boolean writable = false;
        for (final String path : REDMAGIC_HARDWARE_NODES) {
            final File file = new File(path);
            if (file.exists()) {
                present++;
            }
            readable |= file.canRead();
            writable |= file.canWrite();
        }
        append(
                report,
                "hardware.nodes.present",
                Integer.toString(present),
                "expected=" + REDMAGIC_HARDWARE_NODES.length);
        append(report, "hardware.nodes.read", readable ? "granted" : "denied", "");
        append(report, "hardware.nodes.write", writable ? "granted" : "denied", "");
    }

    private static void appendFileAccess(
            final StringBuilder report,
            final String key,
            final File file) {
        append(report, key + ".read", file.canRead() ? "granted" : "denied", "");
        append(report, key + ".write", file.canWrite() ? "granted" : "denied", "");
    }

    private static Object getServiceInterface(
            final String serviceName,
            final String interfaceName) throws ReflectiveOperationException {
        final IBinder binder = getServiceBinder(serviceName);
        if (binder == null) {
            throw new IllegalStateException(serviceName + " service is unavailable");
        }
        return Class.forName(interfaceName + "$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static IBinder getServiceBinder(
            final String serviceName) throws ReflectiveOperationException {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, serviceName);
    }

    private static String readStatusValue(final String name) {
        final String prefix = name + ':';
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/self/status"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix)) {
                    return clean(line.substring(prefix.length()));
                }
            }
        } catch (IOException ignored) {
            // Missing identity data is reported explicitly.
        }
        return "unknown";
    }

    private static String readFirstLine(final String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path),
                StandardCharsets.UTF_8))) {
            return clean(reader.readLine());
        } catch (IOException error) {
            return "unknown:" + usefulMessage(error);
        }
    }

    private static void append(
            final StringBuilder report,
            final String key,
            final String state,
            final String detail) {
        report.append(key).append('=').append(state);
        final String cleaned = clean(detail);
        if (!cleaned.isEmpty()) {
            report.append(" | ").append(cleaned);
        }
        report.append('\n');
    }

    private static String usefulMessage(final Throwable source) {
        final Throwable error = unwrap(source);
        final String message = clean(error.getMessage());
        return error.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message);
    }

    private static Throwable unwrap(final Throwable source) {
        Throwable error = source;
        while ((error instanceof InvocationTargetException
                || error.getClass() == RuntimeException.class)
                && error.getCause() != null
                && error.getCause() != error) {
            error = error.getCause();
        }
        return error;
    }

    static String clean(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }
}
