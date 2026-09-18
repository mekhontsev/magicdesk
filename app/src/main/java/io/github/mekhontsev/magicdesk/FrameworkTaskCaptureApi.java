package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import java.io.IOException;

/** Fresh task-surface capture, available independently of Desktop on API 34+. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class FrameworkTaskCaptureApi {
    record Frame(TaskCapture.Info info, Bitmap bitmap) { }

    Frame capture(final int taskId, final Rect crop) throws ReflectiveOperationException, IOException {
        if (taskId < 0) throw new IllegalArgumentException("invalid task id");
        final CaptureRequest.Region region = crop == null ? null
                : new CaptureRequest.Region(crop.left, crop.top, crop.right, crop.bottom);
        EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.TASK_CAPTURE);
        final Object snapshot = HiddenTaskApi.takeTaskSnapshot(taskId);
        if (snapshot == null) throw new IOException("task " + taskId
                + " has no fresh capture; it may be hidden, unavailable or protected");
        return read(snapshot, taskId, region);
    }

    private Frame read(final Object snapshot, final int taskId, final CaptureRequest.Region region)
            throws ReflectiveOperationException, IOException {
        final Class<?> type = snapshot.getClass();
        Bitmap hardwareBitmap = null;
        Bitmap softwareBitmap = null;
        try (HardwareBuffer buffer = (HardwareBuffer) type.getMethod("getHardwareBuffer").invoke(snapshot)) {
            if (buffer == null || !(Boolean) type.getMethod("isRealSnapshot").invoke(snapshot)) {
                throw new IOException("task capture returned no real image");
            }
            final int width = buffer.getWidth(), height = buffer.getHeight();
            if (width < 1 || height < 1 || width > 8192 || height > 8192) {
                throw new IOException("task capture dimensions exceed supported bounds");
            }
            final Point size = (Point) type.getMethod("getTaskSize").invoke(snapshot);
            if (size == null || size.x < 1 || size.y < 1) throw new IOException("invalid task capture size");
            final CaptureRequest.Region selected = new CaptureRequest(CaptureRequest.Target.TASK,
                    taskId, region).regionFor(width, height);
            final ColorSpace color = (ColorSpace) type.getMethod("getColorSpace").invoke(snapshot);
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, color);
            if (hardwareBitmap == null) throw new IOException("task capture returned no readable bitmap");
            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) throw new IOException("task capture could not be read");
            final int rotation = (Integer) type.getMethod("getRotation").invoke(snapshot);
            final ComponentName activity = (ComponentName) type.getMethod("getTopActivityComponent").invoke(snapshot);
            final TaskCapture.Info info = new TaskCapture.Info(taskId, width, height, size.x, size.y,
                    rotation, activity == null ? "" : activity.flattenToShortString());
            final Bitmap result = Bitmap.createBitmap(softwareBitmap, selected.left(), selected.top(),
                    selected.width(), selected.height());
            if (result == softwareBitmap) softwareBitmap = null;
            return new Frame(info, result);
        } finally {
            if (hardwareBitmap != null) hardwareBitmap.recycle();
            if (softwareBitmap != null) softwareBitmap.recycle();
        }
    }
}
