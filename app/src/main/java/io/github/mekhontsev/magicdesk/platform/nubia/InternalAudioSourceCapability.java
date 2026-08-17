package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;

import android.media.MediaRecorder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Passive probe for Nubia's internal-audio MediaRecorder source. */
final class InternalAudioSourceCapability {
    static final int SOURCE = 80;
    private static final Result CURRENT = inspect(MediaRecorder.class);

    static final class Result {
        final PlatformAudioCaptureDriver.Availability availability;
        final String description;

        Result(
                final PlatformAudioCaptureDriver.Availability availability,
                final String description) {
            this.availability = availability;
            this.description = description;
        }
    }

    private InternalAudioSourceCapability() {
    }

    static Result current() {
        return CURRENT;
    }

    static Result inspect(final Class<?> mediaRecorderClass) {
        try {
            final Method isValid = mediaRecorderClass.getDeclaredMethod(
                    "isValidAudioSource", int.class);
            isValid.setAccessible(true);
            final boolean declared = ((Boolean) isValid.invoke(
                    null, Integer.valueOf(SOURCE))).booleanValue();
            if (!declared) {
                return result(
                        PlatformAudioCaptureDriver.Availability.MISSING,
                        "not recognized by the framework");
            }
            final String name = sourceName(mediaRecorderClass);
            return result(
                    PlatformAudioCaptureDriver.Availability.DECLARED,
                    name == null ? "recognized by the framework"
                            : "name=" + name);
        } catch (NoSuchMethodException error) {
            return result(
                    PlatformAudioCaptureDriver.Availability.UNKNOWN,
                    "framework declaration API is unavailable");
        } catch (IllegalAccessException | InvocationTargetException
                | RuntimeException | LinkageError error) {
            return result(
                    PlatformAudioCaptureDriver.Availability.UNKNOWN,
                    "framework declaration probe failed: "
                            + usefulMessage(error));
        }
    }

    private static String sourceName(final Class<?> mediaRecorderClass) {
        try {
            final Method toName = mediaRecorderClass.getDeclaredMethod(
                    "toLogFriendlyAudioSource", int.class);
            toName.setAccessible(true);
            final Object value = toName.invoke(null, Integer.valueOf(SOURCE));
            final String name = value == null ? "" : value.toString().trim();
            return name.isEmpty() ? null : name;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError ignored) {
            return null;
        }
    }

    private static Result result(
            final PlatformAudioCaptureDriver.Availability availability,
            final String detail) {
        return new Result(
                availability,
                "vendor MediaRecorder source=" + SOURCE + "; " + detail);
    }

    private static String usefulMessage(final Throwable error) {
        final Throwable cause = error instanceof InvocationTargetException
                && error.getCause() != null ? error.getCause() : error;
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }
}
