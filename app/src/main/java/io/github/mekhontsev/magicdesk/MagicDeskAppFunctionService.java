package io.github.mekhontsev.magicdesk;

import android.annotation.TargetApi;
import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionService;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appsearch.GenericDocument;
import android.content.pm.SigningInfo;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Android 16 system-agent adapter for the shared automation gateway. */
@TargetApi(36)
public final class MagicDeskAppFunctionService
        extends AppFunctionService {
    private static final String RESULT_NAMESPACE = "magicdesk-appfunctions";
    private static final String RESULT_SCHEMA = "MagicDeskAutomationResult";
    private static final AtomicLong NEXT_RESULT_ID = new AtomicLong();

    private ExecutorService mExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutor = Executors.newSingleThreadExecutor(runnable -> {
            return new Thread(runnable, "MagicDeskAppFunctions");
        });
    }

    @Override
    public void onDestroy() {
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        super.onDestroy();
    }

    @Override
    public void onExecuteFunction(
            final ExecuteAppFunctionRequest request,
            final String callingPackage,
            final SigningInfo callingPackageSigningInfo,
            final CancellationSignal cancellationSignal,
            final OutcomeReceiver<ExecuteAppFunctionResponse,
                    AppFunctionException> callback) {
        final ExecutorService executor = mExecutor;
        if (executor == null) {
            callback.onError(error(
                    AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                    "MagicDesk automation is unavailable"));
            return;
        }
        final AtomicBoolean delivered = new AtomicBoolean();
        final AtomicReference<Future<?>> future = new AtomicReference<>();
        cancellationSignal.setOnCancelListener(() -> {
            final Future<?> running = future.get();
            if (running != null) {
                running.cancel(true);
            }
            deliverError(
                    delivered,
                    callback,
                    AppFunctionException.ERROR_CANCELLED,
                    "App Function cancelled");
        });
        try {
            future.set(executor.submit(() -> execute(
                    request, cancellationSignal, delivered, callback)));
        } catch (RejectedExecutionException error) {
            deliverError(
                    delivered,
                    callback,
                    AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                    "MagicDesk automation is stopping");
        }
    }

    private void execute(
            final ExecuteAppFunctionRequest request,
            final CancellationSignal cancellationSignal,
            final AtomicBoolean delivered,
            final OutcomeReceiver<ExecuteAppFunctionResponse,
                    AppFunctionException> callback) {
        if (cancellationSignal.isCanceled()) {
            deliverError(
                    delivered,
                    callback,
                    AppFunctionException.ERROR_CANCELLED,
                    "App Function cancelled");
            return;
        }
        try {
            final DesktopAutomationController automation =
                    new DesktopAutomationController(this);
            final String identifier = request.getFunctionIdentifier();
            final GenericDocument parameters = request.getParameters();
            final DesktopAutomationResult result;
            switch (identifier) {
                case MagicDeskAppFunctionCatalog.GET_DESKTOP_STATE:
                    result = DesktopAutomationResult.success(
                            "ok", automation.stateReader().state());
                    break;
                case MagicDeskAppFunctionCatalog.START_DESKTOP:
                    result = automation.execute(
                            "start_desktop",
                            new JSONObject().put(
                                    "target",
                                    optionalString(
                                            parameters,
                                            "target",
                                            "auto")),
                            false);
                    break;
                case MagicDeskAppFunctionCatalog.CLOSE_DESKTOP:
                    result = automation.execute(
                            "close_desktop", new JSONObject(), false);
                    break;
                case MagicDeskAppFunctionCatalog.LAUNCH_APP:
                    result = automation.execute(
                            "launch_app",
                            new JSONObject()
                                    .put("package", requiredString(
                                            parameters, "packageName"))
                                    .put("mode", optionalString(
                                            parameters, "mode", "auto")),
                            false);
                    break;
                case MagicDeskAppFunctionCatalog.OPEN_SETTINGS:
                    result = automation.execute(
                            "open_settings", new JSONObject(), false);
                    break;
                case MagicDeskAppFunctionCatalog.INVOKE_ANDROID_ACTION:
                    final JSONObject actionParameters = new JSONObject(
                            optionalString(
                                    parameters, "parametersJson", "{}"));
                    actionParameters.put(
                            "actionId",
                            requiredString(parameters, "actionId"));
                    result = automation.execute(
                            "invoke_android_action",
                            actionParameters,
                            false);
                    break;
                case MagicDeskAppFunctionCatalog.LIST_ANDROID_ACTIONS:
                    result = automation.execute(
                            "list_android_actions", new JSONObject(), false);
                    break;
                case MagicDeskAppFunctionCatalog.GET_ANDROID_ACTIVITY_RESULT:
                    final JSONObject resultOptions = new JSONObject(
                            optionalString(
                                    parameters, "optionsJson", "{}"));
                    resultOptions.put(
                            "requestId",
                            requiredString(parameters, "requestId"));
                    result = automation.execute(
                            "get_intent_result", resultOptions, false);
                    break;
                default:
                    deliverError(
                            delivered,
                            callback,
                            AppFunctionException.ERROR_FUNCTION_NOT_FOUND,
                            "Unknown MagicDesk App Function");
                    return;
            }
            if (!result.success) {
                deliverError(
                        delivered,
                        callback,
                        AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                        result.message);
                return;
            }
            final GenericDocument response = new GenericDocument.Builder<>(
                    RESULT_NAMESPACE,
                    Long.toString(NEXT_RESULT_ID.incrementAndGet()),
                    RESULT_SCHEMA)
                    .setPropertyString(
                            ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE,
                            result.toJson().toString())
                    .build();
            if (delivered.compareAndSet(false, true)) {
                callback.onResult(new ExecuteAppFunctionResponse(response));
            }
        } catch (IllegalArgumentException error) {
            deliverError(
                    delivered,
                    callback,
                    AppFunctionException.ERROR_INVALID_ARGUMENT,
                    usefulMessage(error));
        } catch (JSONException | RuntimeException error) {
            deliverError(
                    delivered,
                    callback,
                    AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                    usefulMessage(error));
        }
    }

    private static String requiredString(
            final GenericDocument parameters, final String name) {
        final String value = parameters == null
                ? null : parameters.getPropertyString(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String optionalString(
            final GenericDocument parameters,
            final String name,
            final String defaultValue) {
        final String value = parameters == null
                ? null : parameters.getPropertyString(name);
        return value == null || value.trim().isEmpty()
                ? defaultValue : value.trim();
    }

    private static void deliverError(
            final AtomicBoolean delivered,
            final OutcomeReceiver<ExecuteAppFunctionResponse,
                    AppFunctionException> callback,
            final int code,
            final String message) {
        if (delivered.compareAndSet(false, true)) {
            callback.onError(error(code, message));
        }
    }

    private static AppFunctionException error(
            final int code, final String message) {
        return new AppFunctionException(code, message == null ? "" : message);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }
}
