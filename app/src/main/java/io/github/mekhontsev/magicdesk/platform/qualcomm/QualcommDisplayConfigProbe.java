package io.github.mekhontsev.magicdesk.platform.qualcomm;

import io.github.mekhontsev.magicdesk.ShizukuCapabilityProbe;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Read-only probe for Qualcomm's stable display-config Binder service. */
public final class QualcommDisplayConfigProbe {
    private static final String SERVICE =
            "vendor.qti.hardware.display.config.IDisplayConfig/default";
    private static final String DESCRIPTOR =
            "vendor.qti.hardware.display.config.IDisplayConfig";
    private static final int EXTERNAL_DISPLAY = 2;
    private static final int TRANSACTION_IS_DISPLAY_CONNECTED = 1;
    private static final int TRANSACTION_GET_CONFIG_COUNT = 4;
    private static final int TRANSACTION_GET_ACTIVE_CONFIG = 5;
    private static final int TRANSACTION_GET_DISPLAY_ATTRIBUTES = 7;
    private static final int MAX_CONFIGS = 64;

    private QualcommDisplayConfigProbe() {
    }

    public static void main(final String[] args) {
        final StringBuilder report = new StringBuilder();
        appendTo(report);
        System.out.print(report);
    }

    public static void appendTo(final StringBuilder report) {
        try {
            final IBinder service = getService();
            if (service == null) {
                ShizukuCapabilityProbe.append(
                        report, "vendor.qti_display_config", "missing", "");
                return;
            }
            if (!readBoolean(
                    service,
                    TRANSACTION_IS_DISPLAY_CONNECTED,
                    EXTERNAL_DISPLAY)) {
                ShizukuCapabilityProbe.append(
                        report,
                        "vendor.qti_display_config",
                        "available",
                        "external=disconnected");
                return;
            }

            final int count = readInt(
                    service,
                    TRANSACTION_GET_CONFIG_COUNT,
                    EXTERNAL_DISPLAY);
            final int active = readInt(
                    service,
                    TRANSACTION_GET_ACTIVE_CONFIG,
                    EXTERNAL_DISPLAY);
            if (count < 0 || count > MAX_CONFIGS) {
                throw new IllegalStateException(
                        "invalid external config count: " + count);
            }
            final List<String> modes = new ArrayList<>();
            for (int config = 0; config < count; config++) {
                modes.add(readAttributes(service, config).label(config));
            }
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.qti_display_config",
                    "available",
                    "external count=" + count
                            + " active=" + active
                            + " modes=" + modes);
        } catch (ReflectiveOperationException | RemoteException
                | RuntimeException error) {
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.qti_display_config",
                    "unavailable",
                    ShizukuCapabilityProbe.usefulMessage(error));
        }
    }

    private static IBinder getService()
            throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final Method getService = serviceManager.getMethod(
                "getService", String.class);
        return (IBinder) getService.invoke(null, SERVICE);
    }

    private static boolean readBoolean(
            final IBinder service,
            final int transaction,
            final int argument) throws RemoteException {
        return transactInt(service, transaction, argument) != 0;
    }

    private static int readInt(
            final IBinder service,
            final int transaction,
            final int argument) throws RemoteException {
        return transactInt(service, transaction, argument);
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

    private static Attributes readAttributes(
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
            return new Attributes(width, height, vsyncPeriodNanos);
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

    private static final class Attributes {
        final int width;
        final int height;
        final int vsyncPeriodNanos;

        Attributes(
                final int width,
                final int height,
                final int vsyncPeriodNanos) {
            this.width = width;
            this.height = height;
            this.vsyncPeriodNanos = vsyncPeriodNanos;
        }

        String label(final int config) {
            final long refreshRate = vsyncPeriodNanos <= 0
                    ? 0L
                    : Math.round(1_000_000_000d / vsyncPeriodNanos);
            return config + ":" + width + "x" + height + "@" + refreshRate;
        }
    }
}
