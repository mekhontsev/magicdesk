package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One typed gateway for Android intents, published actions, and system agents. */
final class AndroidIntegrationGateway {
    private static final int MAX_SHARED_FILES = 64;
    private static final int MAX_APP_FUNCTION_PARAMETERS_CHARS = 262_144;

    private final Context mContext;
    private final PackageManager mPackageManager;

    AndroidIntegrationGateway(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
        mPackageManager = mContext.getPackageManager();
    }

    DesktopAutomationResult queryIntentHandlers(final JSONObject args)
            throws IOException, JSONException {
        if (ShellAccess.isReady()) {
            return shellGatewayResult(ShellAccess.queryIntentHandlers(
                    args == null ? "{}" : args.toString()));
        }
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.ACTIVITY);
        return DesktopAutomationResult.success(
                "Android handlers resolved",
                AndroidIntentHandlerQuery.query(
                        mPackageManager,
                        request,
                        args.optInt("limit", 100),
                        "application"));
    }

    DesktopAutomationResult launchIntent(final JSONObject args)
            throws IOException, JSONException {
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.ACTIVITY);
        if (request.kind != AndroidIntegrationRequest.Kind.ACTIVITY) {
            throw new IllegalArgumentException(
                    "launch_intent requires kind=activity");
        }
        return launchActivity(request, optionalDisplayId(args));
    }

    DesktopAutomationResult openUri(final JSONObject args)
            throws IOException, JSONException {
        final JSONObject request = new JSONObject(args.toString());
        request.put("kind", AndroidIntegrationRequest.Kind.ACTIVITY.wireName)
                .put("action", Intent.ACTION_VIEW)
                .put("dataUri", requiredString(args, "uri"));
        request.remove("uri");
        return launchActivity(
                AndroidIntegrationRequest.parse(
                        request, AndroidIntegrationRequest.Kind.ACTIVITY),
                optionalDisplayId(args));
    }

    DesktopAutomationResult openFile(final JSONObject args)
            throws IOException, JSONException {
        final Uri uri;
        String mimeType = optionalString(args, "mimeType", "");
        final String path = optionalString(args, "path", "");
        final String uriValue = optionalString(args, "uri", "");
        if (path.isEmpty() == uriValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "provide exactly one of path or uri");
        }
        final boolean writable = args.optBoolean("writable", false);
        if (!path.isEmpty()) {
            if (!ShellAccess.isReady()) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                        "shell command service is unavailable", true);
            }
            final ShellFileInfo file = ShellAccess.getShellFileInfo(path);
            if (file.directory) {
                throw new IllegalArgumentException("path must identify a file");
            }
            uri = ShellFileGrantStore.create(
                    mContext, file, writable && file.writable);
            if (mimeType.isEmpty()) {
                mimeType = file.mimeType;
            }
        } else {
            uri = Uri.parse(uriValue);
        }
        if (mimeType.isEmpty()) {
            mimeType = "application/octet-stream";
        }
        final String operation = optionalString(args, "operation", "view");
        final String action;
        if ("view".equals(operation)) {
            action = Intent.ACTION_VIEW;
        } else if ("edit".equals(operation)) {
            action = Intent.ACTION_EDIT;
        } else {
            throw new IllegalArgumentException("operation must be view or edit");
        }
        final AndroidContentPayload content = AndroidContentPayload.uris(
                optionalString(args, "name", "Open file"),
                List.of(new AndroidContentPayload.UriItem(uri, mimeType)),
                Collections.emptyList(),
                AndroidContentPayload.Origin.APPLICATION);
        final Intent intent = AndroidContentIntentAdapter.open(content)
                .setAction(action);
        if (writable) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        applyTarget(intent, args);
        return launchActivity(
                AndroidIntegrationRequest.activity(
                        intent,
                        optionalString(args, "name", "Open file"),
                        parseMode(args),
                        args.optBoolean("chooser", false),
                        optionalString(args, "chooserTitle", ""),
                        args.optBoolean("expectResult", false)),
                optionalDisplayId(args));
    }

    DesktopAutomationResult share(final JSONObject args)
            throws IOException, JSONException {
        final String text = optionalString(args, "text", "");
        final String subject = optionalString(args, "subject", "");
        final JSONArray files = args.optJSONArray("files");
        final ArrayList<AndroidContentPayload.UriItem> uriItems =
                new ArrayList<>();
        String inferredMime = "";
        if (files != null) {
            if (files.length() > MAX_SHARED_FILES) {
                throw new IllegalArgumentException(
                        "share accepts at most " + MAX_SHARED_FILES + " files");
            }
            for (int index = 0; index < files.length(); index++) {
                final String value = files.getString(index).trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("files must not contain empty paths");
                }
                if (value.startsWith("content://")) {
                    uriItems.add(new AndroidContentPayload.UriItem(
                            Uri.parse(value), "*/*"));
                } else {
                    if (!ShellAccess.isReady()) {
                        return DesktopAutomationResult.failure(
                                DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                                "shell command service is unavailable", true);
                    }
                    final ShellFileInfo file = ShellAccess.getShellFileInfo(value);
                    if (file.directory) {
                        throw new IllegalArgumentException(
                                "share files must not include directories");
                    }
                    uriItems.add(new AndroidContentPayload.UriItem(
                            ShellFileGrantStore.create(mContext, file, false),
                            file.mimeType));
                    if (inferredMime.isEmpty()) {
                        inferredMime = file.mimeType;
                    } else if (!inferredMime.equals(file.mimeType)) {
                        inferredMime = "*/*";
                    }
                }
            }
        }
        if (text.isEmpty() && uriItems.isEmpty()) {
            throw new IllegalArgumentException("share requires text or files");
        }
        final String requestedMime = optionalString(
                args,
                "mimeType",
                inferredMime.isEmpty() ? "text/plain" : inferredMime);
        final AndroidContentPayload content = AndroidContentPayload.create(
                AndroidContentPayload.Origin.APPLICATION,
                optionalString(args, "name", "Share"),
                subject,
                text,
                "",
                uriItems,
                List.of(requestedMime),
                false);
        final Intent intent = AndroidContentIntentAdapter.share(content);
        applyTarget(intent, args);
        return launchActivity(
                AndroidIntegrationRequest.activity(
                        intent,
                        optionalString(args, "name", "Share"),
                        parseMode(args),
                        args.optBoolean("chooser", true),
                        optionalString(args, "chooserTitle", "Share with"),
                        false),
                optionalDisplayId(args));
    }

    DesktopAutomationResult openContent(
            final AndroidContentPayload content,
            final int displayId) throws IOException, JSONException {
        final Intent intent = AndroidContentIntentAdapter.open(content);
        if (intent == null) {
            return DesktopAutomationResult.failure(
                    "clipboard content cannot be opened");
        }
        return launchActivity(
                AndroidIntegrationRequest.activity(
                        intent,
                        content.label.isEmpty()
                                ? "Clipboard content" : content.label,
                        DesktopLaunchMode.AUTO,
                        false,
                        "",
                        false),
                displayId);
    }

    DesktopAutomationResult shareContent(
            final AndroidContentPayload content,
            final int displayId) throws IOException, JSONException {
        final Intent intent = AndroidContentIntentAdapter.share(content);
        if (intent == null) {
            return DesktopAutomationResult.failure(
                    "clipboard content cannot be shared");
        }
        return launchActivity(
                AndroidIntegrationRequest.activity(
                        intent,
                        "Share clipboard content",
                        DesktopLaunchMode.AUTO,
                        true,
                        "Share with",
                        false),
                displayId);
    }

    DesktopAutomationResult sendBroadcast(final JSONObject args)
            throws JSONException {
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.BROADCAST);
        if (request.kind != AndroidIntegrationRequest.Kind.BROADCAST) {
            throw new IllegalArgumentException(
                    "send_broadcast requires kind=broadcast");
        }
        mContext.sendBroadcast(request.intent);
        return DesktopAutomationResult.success(
                "broadcast sent", describeExecution(request.intent, request.kind));
    }

    DesktopAutomationResult startService(final JSONObject args)
            throws JSONException {
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.SERVICE);
        if (request.kind != AndroidIntegrationRequest.Kind.SERVICE) {
            throw new IllegalArgumentException(
                    "start_service requires kind=service");
        }
        final ComponentName started = request.foregroundService
                ? mContext.startForegroundService(request.intent)
                : mContext.startService(request.intent);
        if (started == null) {
            return DesktopAutomationResult.failure(
                    "Android did not resolve the requested service");
        }
        return DesktopAutomationResult.success(
                "service start accepted",
                describeExecution(request.intent, request.kind)
                        .put("component", started.flattenToShortString())
                        .put("foreground", request.foregroundService));
    }

    DesktopAutomationResult listAppActions(final JSONObject args)
            throws JSONException {
        final AppLaunchTarget target = appTarget(args);
        final JSONArray actions = new JSONArray();
        for (final AppShortcutAction action
                : new AppShortcutRepository(mContext).loadAll(target)) {
            actions.put(new JSONObject()
                    .put("id", action.id)
                    .put("label", action.label)
                    .put("source", action.source)
                    .put("action", action.actionName())
                    .put("component", action.componentName()));
        }
        return DesktopAutomationResult.success(
                "application actions listed",
                new JSONObject()
                        .put("package", target.packageName)
                        .put("actions", actions));
    }

    DesktopAutomationResult invokeAppAction(final JSONObject args)
            throws JSONException {
        final AppLaunchTarget target = appTarget(args);
        final String actionId = requiredString(args, "actionId");
        if (!DesktopRuntimeBridge.invokeAppAction(target, actionId)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "application action was not found or could not launch",
                    false,
                    new JSONObject()
                            .put("package", target.packageName)
                            .put("actionId", actionId));
        }
        return DesktopAutomationResult.success(
                "application action launch completed",
                new JSONObject()
                        .put("package", target.packageName)
                        .put("actionId", actionId));
    }

    DesktopAutomationResult listNotifications(final JSONObject args)
            throws JSONException {
        final DesktopNotificationListenerService.Snapshot snapshot =
                DesktopNotificationListenerService.getSnapshot();
        final String packageFilter = optionalString(args, "package", "");
        final JSONArray entries = new JSONArray();
        for (final DesktopNotificationListenerService.Entry entry
                : snapshot.entries) {
            if (!packageFilter.isEmpty()
                    && !packageFilter.equals(entry.packageName)) {
                continue;
            }
            final JSONArray actions = new JSONArray();
            for (final DesktopNotificationListenerService.ActionEntry action
                    : entry.actions) {
                actions.put(new JSONObject()
                        .put("index", action.index)
                        .put("title", action.title));
            }
            entries.put(new JSONObject()
                    .put("key", entry.key)
                    .put("package", entry.packageName)
                    .put("appName", entry.appName)
                    .put("title", entry.title)
                    .put("text", entry.text)
                    .put("postTime", entry.postTime)
                    .put("importance", entry.importance)
                    .put("hasContentIntent", entry.hasContentIntent)
                    .put("clearable", entry.clearable)
                    .put("ongoing", entry.ongoing)
                    .put("actions", actions));
        }
        return DesktopAutomationResult.success(
                "notifications listed",
                new JSONObject()
                        .put("connected", snapshot.connected)
                        .put("connectionIssue", snapshot.connectionIssueCode)
                        .put("unreadCount", snapshot.unreadCount)
                        .put("notifications", entries));
    }

    DesktopAutomationResult invokeNotification(final JSONObject args)
            throws JSONException {
        final String key = requiredString(args, "key");
        final String operation = optionalString(args, "operation", "open");
        final boolean success;
        switch (operation) {
            case "open":
                success = DesktopNotificationListenerService.openNotification(
                        mContext, key, optionalDisplayId(args));
                break;
            case "action":
                success = DesktopNotificationListenerService.invokeAction(
                        mContext,
                        key,
                        requiredInt(args, "actionIndex"),
                        optionalDisplayId(args));
                break;
            case "dismiss":
                success = DesktopNotificationListenerService.dismissNotification(key);
                break;
            default:
                throw new IllegalArgumentException(
                        "operation must be open, action, or dismiss");
        }
        final JSONObject data = new JSONObject()
                .put("key", key)
                .put("operation", operation);
        return success
                ? DesktopAutomationResult.success(
                        "notification operation accepted", data)
                : DesktopAutomationResult.failure(
                        "notification operation was unavailable", data);
    }

    DesktopAutomationResult getActivityResult(final JSONObject args)
            throws JSONException {
        final JSONObject result = AndroidActivityResultStore.get(
                requiredString(args, "requestId"));
        return DesktopAutomationResult.success(
                "Activity result state read", result);
    }

    DesktopAutomationResult searchAppFunctions(final JSONObject args)
            throws IOException, JSONException {
        if (!ShellAccess.isReady()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                    "shell command service is unavailable", true);
        }
        return shellGatewayResult(ShellAccess.searchAppFunctions(
                args.toString(), timeoutMillis(args)));
    }

    DesktopAutomationResult executeAppFunction(final JSONObject args)
            throws IOException, JSONException {
        if (!ShellAccess.isReady()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                    "shell command service is unavailable", true);
        }
        final String packageName = requiredString(args, "package");
        final String functionId = requiredString(args, "functionId");
        final JSONObject parameters = args.optJSONObject("parameters");
        final String encodedParameters = parameters == null
                ? "{}" : parameters.toString();
        if (encodedParameters.length()
                > MAX_APP_FUNCTION_PARAMETERS_CHARS) {
            throw new IllegalArgumentException(
                    "App Function parameters are too large");
        }
        return shellGatewayResult(ShellAccess.executeAppFunction(
                packageName,
                functionId,
                encodedParameters,
                timeoutMillis(args)));
    }

    private static DesktopAutomationResult shellGatewayResult(
            final String encoded) throws JSONException {
        final JSONObject response = new JSONObject(encoded);
        final String message = response.optString("message", "");
        final JSONObject data = response.optJSONObject("data");
        return response.optBoolean("success", false)
                ? DesktopAutomationResult.success(
                        message.isEmpty() ? "Android operation completed" : message,
                        data == null ? new JSONObject() : data)
                : DesktopAutomationResult.failure(
                        message.isEmpty() ? "Android operation failed" : message,
                        data == null ? new JSONObject() : data);
    }

    private static long timeoutMillis(final JSONObject args) {
        return Math.max(1_000L, Math.min(
                60_000L, args.optLong("timeoutMillis", 20_000L)));
    }

    private DesktopAutomationResult launchActivity(
            final AndroidIntegrationRequest request,
            final int displayId) throws IOException, JSONException {
        final Intent target = new Intent(request.intent);
        final AndroidActivityResolution resolution = ShellAccess.isReady()
                ? ShellAccess.resolveActivity(target)
                : AndroidActivityResolution.resolve(mPackageManager, target);
        final ComponentName resolvedComponent = resolution.component;
        if (!resolution.hasHandlers()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "no visible Activity handles the requested Intent",
                    false,
                    describeExecution(target, request.kind));
        }
        final boolean useRelay = request.chooser || request.expectResult
                || resolution.requiresResolver();
        if (!useRelay) {
            target.setComponent(resolvedComponent);
        }
        // Direct desktop launches are executed by shell. Issue grants from the
        // app identity after resolution because shell does not own these URIs.
        grantKnownTarget(target, grantUris(target));
        final Intent launchedIntent;
        final String resultRequestId;
        final String relayId;
        if (request.expectResult) {
            resultRequestId = AndroidActivityResultStore.begin(target);
        } else {
            resultRequestId = "";
        }
        if (useRelay) {
            // Keep nested intents in the app process. Passing them through the
            // shell drops the creator identity required by Android 16 redirect
            // hardening even though the outer Intent itself is Parcelable.
            relayId = AndroidActivityRelayStore.put(
                    target, request.chooser, request.chooserTitle);
            launchedIntent = AndroidActivityRelayActivity.createIntent(
                    mContext, relayId, resultRequestId);
        } else {
            relayId = "";
            launchedIntent = target;
        }
        final ComponentName component = launchedIntent.getComponent();
        final AppLaunchTarget transportTarget = AppLaunchTarget.explicit(
                component.getPackageName(),
                component.getClassName(),
                launchedIntent.getAction());
        final AppLaunchTarget taskTarget = useRelay
                && resolvedComponent != null
                ? AppLaunchTarget.packageDefault(
                        resolvedComponent.getPackageName())
                : transportTarget;
        final DesktopLaunchRequest desktopRequest = new DesktopLaunchRequest(
                request.name,
                taskTarget.packageName,
                AndroidLaunchSpec.intent(
                        taskTarget,
                        launchedIntent),
                null,
                request.launchMode);
        if (!DesktopRuntimeBridge.launchAutomationRequest(
                desktopRequest, displayId)) {
            AndroidActivityRelayStore.discard(relayId);
            if (!resultRequestId.isEmpty()) {
                AndroidActivityResultStore.fail(
                        resultRequestId,
                        new IllegalStateException("desktop host is unavailable"));
            }
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    "desktop launch request was not accepted", true);
        }
        final JSONObject data = describeExecution(target, request.kind)
                .put("displayId", displayId)
                .put("mode", request.launchMode.wireName)
                .put("resolvedComponent",
                        resolvedComponent == null
                                ? "" : resolvedComponent.flattenToShortString())
                .put("resolution", resolution.stateName())
                .put("handlerCount", resolution.handlerCount)
                .put("relay", useRelay)
                .put("resultExpected", request.expectResult);
        if (!resultRequestId.isEmpty()) {
            data.put("requestId", resultRequestId);
        }
        return DesktopAutomationResult.success(
                "Android Activity launch accepted", data);
    }

    private int optionalDisplayId(final JSONObject args) {
        final int active = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final int requested = args != null && args.has("displayId")
                ? requiredInt(args, "displayId") : active;
        if (requested < Display.DEFAULT_DISPLAY || requested != active) {
            throw new IllegalArgumentException(
                    "the requested display has no active desktop host");
        }
        return requested;
    }

    private static JSONObject describeExecution(
            final Intent intent,
            final AndroidIntegrationRequest.Kind kind) throws JSONException {
        return new JSONObject()
                .put("kind", kind.wireName)
                .put("action", value(intent.getAction()))
                .put("dataUri", value(intent.getDataString()))
                .put("mimeType", value(intent.getType()))
                .put("package", value(intent.getPackage()))
                .put("component", intent.getComponent() == null
                        ? "" : intent.getComponent().flattenToShortString());
    }

    private void grantKnownTarget(
            final Intent intent,
            final List<Uri> uris) {
        String packageName = intent.getPackage();
        if ((packageName == null || packageName.isEmpty())
                && intent.getComponent() != null) {
            packageName = intent.getComponent().getPackageName();
        }
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        final boolean readable = (intent.getFlags()
                & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        final boolean writable = (intent.getFlags()
                & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        if (!readable && !writable) {
            return;
        }
        for (final Uri uri : uris) {
            if (readable) {
                mContext.grantUriPermission(
                        packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (writable) {
                mContext.grantUriPermission(
                        packageName,
                        uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
    }

    private static List<Uri> grantUris(final Intent intent) {
        final ArrayList<Uri> result = new ArrayList<>();
        if (intent == null) {
            return result;
        }
        addGrantUri(result, intent.getData());
        final ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                addGrantUri(result, clip.getItemAt(index).getUri());
            }
        }
        return result;
    }

    private static void addGrantUri(
            final List<Uri> uris,
            final Uri uri) {
        if (uri != null
                && "content".equalsIgnoreCase(uri.getScheme())
                && !uris.contains(uri)) {
            uris.add(uri);
        }
    }

    private static void applyTarget(
            final Intent intent,
            final JSONObject args) {
        final String packageName = optionalString(args, "package", "");
        final String componentValue = optionalString(args, "component", "");
        if (!componentValue.isEmpty()) {
            final ComponentName component = ComponentName.unflattenFromString(
                    componentValue);
            if (component == null) {
                throw new IllegalArgumentException("invalid component");
            }
            if (!packageName.isEmpty()
                    && !packageName.equals(component.getPackageName())) {
                throw new IllegalArgumentException(
                        "component must belong to package");
            }
            intent.setComponent(component);
        } else if (!packageName.isEmpty()) {
            intent.setPackage(packageName);
        }
    }

    private static AppLaunchTarget appTarget(final JSONObject args) {
        final String packageName = requiredString(args, "package");
        final String componentValue = optionalString(args, "component", "");
        if (componentValue.isEmpty()) {
            return AppLaunchTarget.packageDefault(packageName);
        }
        final ComponentName component = ComponentName.unflattenFromString(
                componentValue);
        if (component == null
                || !packageName.equals(component.getPackageName())) {
            throw new IllegalArgumentException(
                    "component must belong to package");
        }
        return AppLaunchTarget.explicit(
                packageName, component.getClassName(), Intent.ACTION_MAIN);
    }

    private static DesktopLaunchMode parseMode(final JSONObject args) {
        final String value = optionalString(args, "mode", "auto");
        for (final DesktopLaunchMode mode : DesktopLaunchMode.values()) {
            if (mode.wireName.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "mode must be auto, windowed, or fullscreen");
    }

    private static int requiredInt(
            final JSONObject args,
            final String name) {
        if (args == null || !args.has(name)) {
            throw new IllegalArgumentException(name + " is required");
        }
        final Object value = args.opt(name);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return (int) number;
    }

    private static String requiredString(
            final JSONObject args,
            final String name) {
        final String value = optionalString(args, name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String optionalString(
            final JSONObject args,
            final String name,
            final String fallback) {
        if (args == null) {
            return fallback;
        }
        final String value = args.optString(name, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String value(final String value) {
        return value == null ? "" : value;
    }
}
