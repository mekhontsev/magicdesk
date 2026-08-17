package io.github.mekhontsev.magicdesk.soc.qualcomm;

import io.github.mekhontsev.magicdesk.AppProcessCommand;
import io.github.mekhontsev.magicdesk.ShellAccess;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shell bridge for Qualcomm's stable external-display configuration API. */
public final class QualcommDisplayConfigBridge {
    private static final String SERVICE =
            "vendor.qti.hardware.display.config.IDisplayConfig/default";
    private static final String DESCRIPTOR =
            "vendor.qti.hardware.display.config.IDisplayConfig";
    private static final String COMMAND_CLASS =
            "io.github.mekhontsev.magicdesk.soc.qualcomm."
                    + "QualcommDisplayConfigBridge";
    private static final String STATUS_PREFIX = "status=";
    private static final String ACTIVE_PREFIX = "active=";
    private static final String MODE_PREFIX = "mode=";
    private static final int EXTERNAL_DISPLAY = 2;
    private static final int TRANSACTION_IS_DISPLAY_CONNECTED = 1;
    private static final int TRANSACTION_GET_CONFIG_COUNT = 4;
    private static final int TRANSACTION_GET_ACTIVE_CONFIG = 5;
    private static final int TRANSACTION_SET_ACTIVE_CONFIG = 6;
    private static final int TRANSACTION_GET_DISPLAY_ATTRIBUTES = 7;
    private static final int MAX_CONFIGS = 64;

    private QualcommDisplayConfigBridge() {
    }

    public static Snapshot queryExternal() throws IOException {
        return parse(ShellAccess.run(
                AppProcessCommand.run(COMMAND_CLASS, "query")));
    }

    public static void applyExternalTiming(final String timingKey)
            throws IOException {
        if (!isTimingKey(timingKey)) {
            throw new IOException("invalid Qualcomm display timing: "
                    + timingKey);
        }
        final String output = ShellAccess.run(AppProcessCommand.run(
                COMMAND_CLASS, "set " + timingKey)).trim();
        if (!output.equals("applied=" + timingKey)) {
            throw new IOException(
                    "Qualcomm display mode was rejected: " + output);
        }
    }

    public static void main(final String[] args) {
        try {
            if (args.length == 1 && "query".equals(args[0])) {
                System.out.print(encode(queryDirect()));
                return;
            }
            if (args.length == 2 && "set".equals(args[0])) {
                applyDirect(args[1]);
                System.out.println("applied=" + args[1]);
                return;
            }
            throw new IllegalArgumentException("usage: query | set TIMING");
        } catch (ReflectiveOperationException | RemoteException
                | RuntimeException | LinkageError error) {
            // An OEM may expose the service with a different Binder revision.
            // Report an unavailable backend to the caller without producing an
            // Android crash record from this short-lived app_process command.
            System.err.println("Qualcomm display-config failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty()
                        ? "" : ": " + message.trim().replace('\n', ' '));
    }

    static Snapshot queryDirect()
            throws ReflectiveOperationException, RemoteException {
        final IBinder service = getService();
        if (service == null) {
            return Snapshot.missing();
        }
        if (transactInt(
                service,
                TRANSACTION_IS_DISPLAY_CONNECTED,
                EXTERNAL_DISPLAY) == 0) {
            return Snapshot.disconnected();
        }
        final int count = transactInt(
                service, TRANSACTION_GET_CONFIG_COUNT, EXTERNAL_DISPLAY);
        final int active = transactInt(
                service, TRANSACTION_GET_ACTIVE_CONFIG, EXTERNAL_DISPLAY);
        if (count < 0 || count > MAX_CONFIGS) {
            throw new IllegalStateException(
                    "invalid external config count: " + count);
        }
        final List<Config> configs = new ArrayList<>();
        for (int config = 0; config < count; config++) {
            configs.add(readAttributes(service, config));
        }
        return Snapshot.connected(active, configs);
    }

    static Snapshot parse(final String output) throws IOException {
        String status = null;
        int active = -1;
        final List<Config> configs = new ArrayList<>();
        if (output != null) {
            for (String line : output.split("\\R")) {
                line = line.trim();
                try {
                    if (line.startsWith(STATUS_PREFIX)) {
                        status = line.substring(STATUS_PREFIX.length());
                    } else if (line.startsWith(ACTIVE_PREFIX)) {
                        active = Integer.parseInt(
                                line.substring(ACTIVE_PREFIX.length()));
                    } else if (line.startsWith(MODE_PREFIX)) {
                        final String[] fields = line.substring(
                                MODE_PREFIX.length()).split(",", -1);
                        if (fields.length != 4) {
                            throw new IOException(
                                    "invalid Qualcomm mode: " + line);
                        }
                        configs.add(new Config(
                                Integer.parseInt(fields[0]),
                                Integer.parseInt(fields[1]),
                                Integer.parseInt(fields[2]),
                                Integer.parseInt(fields[3])));
                    }
                } catch (NumberFormatException error) {
                    throw new IOException(
                            "invalid Qualcomm display response: " + line,
                            error);
                }
            }
        }
        if ("missing".equals(status)) {
            return Snapshot.missing();
        }
        if ("disconnected".equals(status)) {
            return Snapshot.disconnected();
        }
        if (!"connected".equals(status) || active < 0) {
            throw new IOException("invalid Qualcomm display response: "
                    + (output == null ? "" : output.trim()));
        }
        return Snapshot.connected(active, configs);
    }

    private static String encode(final Snapshot snapshot) {
        final StringBuilder output = new StringBuilder();
        if (!snapshot.available) {
            return output.append(STATUS_PREFIX).append("missing\n").toString();
        }
        if (!snapshot.connected) {
            return output.append(STATUS_PREFIX)
                    .append("disconnected\n").toString();
        }
        output.append(STATUS_PREFIX).append("connected\n")
                .append(ACTIVE_PREFIX).append(snapshot.activeConfig)
                .append('\n');
        for (Config config : snapshot.configs) {
            output.append(MODE_PREFIX)
                    .append(config.index).append(',')
                    .append(config.width).append(',')
                    .append(config.height).append(',')
                    .append(config.refreshRate).append('\n');
        }
        return output.toString();
    }

    private static void applyDirect(final String timingKey)
            throws ReflectiveOperationException, RemoteException {
        if (!isTimingKey(timingKey)) {
            throw new IllegalArgumentException(
                    "invalid Qualcomm display timing: " + timingKey);
        }
        final IBinder service = getService();
        if (service == null) {
            throw new IllegalStateException(
                    "Qualcomm display-config service is missing");
        }
        final Snapshot snapshot = queryDirect();
        final Config target = snapshot.findTiming(timingKey);
        if (!snapshot.connected || target == null) {
            throw new IllegalStateException(
                    "Qualcomm display timing is unavailable: " + timingKey);
        }
        if (snapshot.activeConfig == target.index) {
            return;
        }
        final Parcel request = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(EXTERNAL_DISPLAY);
            request.writeInt(target.index);
            transact(
                    service,
                    TRANSACTION_SET_ACTIVE_CONFIG,
                    request,
                    reply);
            reply.readException();
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static IBinder getService()
            throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final Method getService = serviceManager.getMethod(
                "getService", String.class);
        return (IBinder) getService.invoke(null, SERVICE);
    }

    private static int transactInt(
            final IBinder service,
            final int transaction,
            final int argument) throws RemoteException {
        final Parcel request = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(argument);
            transact(service, transaction, request, reply);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static Config readAttributes(
            final IBinder service,
            final int config) throws RemoteException {
        final Parcel request = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(config);
            request.writeInt(EXTERNAL_DISPLAY);
            transact(
                    service,
                    TRANSACTION_GET_DISPLAY_ATTRIBUTES,
                    request,
                    reply);
            reply.readException();
            if (reply.readInt() == 0) {
                throw new IllegalStateException(
                        "missing attributes for external config " + config);
            }
            final int start = reply.dataPosition();
            final int size = reply.readInt();
            final int end = start + size;
            if (size < 16 || end < start || end > reply.dataSize()) {
                throw new IllegalStateException(
                        "invalid attributes parcel size: " + size);
            }
            final int vsyncPeriodNanos = reply.readInt();
            final int width = reply.readInt();
            final int height = reply.readInt();
            reply.setDataPosition(end);
            final int refreshRate = vsyncPeriodNanos <= 0
                    ? 0
                    : (int) Math.round(
                            1_000_000_000d / vsyncPeriodNanos);
            return new Config(config, width, height, refreshRate);
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static void transact(
            final IBinder service,
            final int transaction,
            final Parcel request,
            final Parcel reply) throws RemoteException {
        if (!service.transact(transaction, request, reply, 0)) {
            throw new IllegalStateException(
                    "unsupported display-config transaction " + transaction);
        }
    }

    private static boolean isTimingKey(final String timingKey) {
        return timingKey != null
                && timingKey.matches("[1-9][0-9]*x[1-9][0-9]*@[1-9][0-9]*");
    }

    public static final class Snapshot {
        public final boolean available;
        public final boolean connected;
        public final int activeConfig;
        public final List<Config> configs;

        private Snapshot(
                final boolean available,
                final boolean connected,
                final int activeConfig,
                final List<Config> configs) {
            this.available = available;
            this.connected = connected;
            this.activeConfig = activeConfig;
            this.configs = Collections.unmodifiableList(
                    new ArrayList<>(configs));
        }

        static Snapshot missing() {
            return new Snapshot(false, false, -1, Collections.emptyList());
        }

        static Snapshot disconnected() {
            return new Snapshot(true, false, -1, Collections.emptyList());
        }

        static Snapshot connected(
                final int activeConfig,
                final List<Config> configs) {
            return new Snapshot(true, true, activeConfig, configs);
        }

        public Config active() {
            for (Config config : configs) {
                if (config.index == activeConfig) {
                    return config;
                }
            }
            return null;
        }

        public Config findTiming(final String timingKey) {
            Config first = null;
            for (Config config : configs) {
                if (config.timingKey().equals(timingKey)) {
                    if (config.index == activeConfig) {
                        return config;
                    }
                    if (first == null) {
                        first = config;
                    }
                }
            }
            return first;
        }
    }

    public static final class Config {
        public final int index;
        public final int width;
        public final int height;
        public final int refreshRate;

        Config(
                final int index,
                final int width,
                final int height,
                final int refreshRate) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        public String timingKey() {
            return width + "x" + height + "@" + refreshRate;
        }

        public String label() {
            return index + ":" + timingKey();
        }
    }
}
