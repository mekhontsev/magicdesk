package io.github.mekhontsev.magicdesk;

import android.annotation.TargetApi;
import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appsearch.GenericDocument;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the Android 16 App Functions framework from the shell identity. */
final class AppFunctionCapabilityProbe {
    private static final long TIMEOUT_SECONDS = 10L;

    private AppFunctionCapabilityProbe() {
    }

    static void append(
            final StringBuilder report,
            final Context context) {
        if (Build.VERSION.SDK_INT < 36) {
            ShizukuCapabilityProbe.append(
                    report,
                    "app_functions.execute",
                    "unavailable",
                    "requires Android 16");
            return;
        }
        Api36.append(report, context);
    }

    static boolean isSuccessfulResponse(final String response) {
        if (response == null || response.trim().isEmpty()) {
            return false;
        }
        try {
            return new JSONObject(response).optBoolean("success", false);
        } catch (JSONException error) {
            return false;
        }
    }

    @TargetApi(36)
    private static final class Api36 {
        private Api36() {
        }

        static void append(
                final StringBuilder report,
                final Context context) {
            if (context == null) {
                append(report, "unknown", "no service context");
                return;
            }
            final Context shellContext;
            try {
                shellContext = ShellIdentityContext.create(context);
            } catch (PackageManager.NameNotFoundException error) {
                append(report, "unavailable", "Android shell package missing");
                return;
            }
            final AppFunctionManager manager = shellContext.getSystemService(
                    AppFunctionManager.class);
            if (manager == null) {
                append(report, "unavailable", "framework service missing");
                return;
            }

            final CountDownLatch completed = new CountDownLatch(1);
            final AtomicReference<ExecuteAppFunctionResponse> response =
                    new AtomicReference<>();
            final AtomicReference<AppFunctionException> failure =
                    new AtomicReference<>();
            try {
                manager.executeAppFunction(
                        new ExecuteAppFunctionRequest.Builder(
                                BuildConfig.APPLICATION_ID,
                                MagicDeskAppFunctionCatalog.GET_DESKTOP_STATE)
                                .build(),
                        Runnable::run,
                        new CancellationSignal(),
                        new OutcomeReceiver<ExecuteAppFunctionResponse,
                                AppFunctionException>() {
                            @Override
                            public void onResult(
                                    final ExecuteAppFunctionResponse result) {
                                response.set(result);
                                completed.countDown();
                            }

                            @Override
                            public void onError(
                                    final AppFunctionException error) {
                                failure.set(error);
                                completed.countDown();
                            }
                        });
                if (!completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    append(report, "error", "getDesktopState timed out");
                    return;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                append(report, "error", "probe interrupted");
                return;
            } catch (RuntimeException error) {
                append(report, "denied",
                        ShizukuCapabilityProbe.usefulMessage(error));
                return;
            }

            final AppFunctionException error = failure.get();
            if (error != null) {
                append(report, "denied", "code=" + error.getErrorCode()
                        + " " + error.getErrorMessage());
                return;
            }
            final ExecuteAppFunctionResponse result = response.get();
            final GenericDocument document = result == null
                    ? null : result.getResultDocument();
            final String value = document == null ? null
                    : document.getPropertyString(
                            ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE);
            final boolean successful = isSuccessfulResponse(value);
            append(report,
                    successful ? "granted" : "error",
                    successful
                            ? "getDesktopState returned structured state"
                            : "invalid getDesktopState response");
        }

        private static void append(
                final StringBuilder report,
                final String state,
                final String detail) {
            ShizukuCapabilityProbe.append(
                    report, "app_functions.execute", state, detail);
        }
    }
}
