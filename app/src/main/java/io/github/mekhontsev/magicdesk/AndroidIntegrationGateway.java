package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.AutomationJsonArguments.requiredInt;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One typed gateway for Android intents, published actions, and system agents. */
final class AndroidIntegrationGateway {
    private static final int MAX_APP_FUNCTION_PARAMETERS_CHARS = 262_144;
    private static final long LAUNCH_OBSERVE_TIMEOUT_MILLIS = 10_000L;

    private final Context mContext;
    private final PackageManager mPackageManager;

    AndroidIntegrationGateway(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
        mPackageManager = mContext.getPackageManager();
    }

    DesktopAutomationResult execute(
            final AndroidDesktopAction action,
            final int displayId) throws IOException, JSONException {
        return execute(action, ToolLaunchTarget.resolve("auto", displayId,
                DesktopRuntimeBridge.getActiveDesktopDisplayId()));
    }

    private DesktopAutomationResult execute(
            final AndroidDesktopAction action,
            final ToolLaunchTarget placement) throws IOException, JSONException {
        if (action == null) {
            throw new IllegalArgumentException("Android action is required");
        }
        if (action.application != null) {
            AppProfile.requireCurrent(mContext, action.application);
        }
        final int displayId = placement.displayId;
        if (action.kind == AndroidDesktopAction.Kind.SHORTCUT
                || action.kind == AndroidDesktopAction.Kind.PENDING_INTENT
                        && action.pendingIntent.isActivity()
                || action.kind == AndroidDesktopAction.Kind.REQUEST
                        && action.request.kind == AndroidIntegrationRequest.Kind.ACTIVITY) {
            requireLaunchTarget(placement, action.presentation);
        }
        final DesktopAutomationResult result;
        if (action.kind == AndroidDesktopAction.Kind.SHORTCUT) {
            result = executeShortcut(action, placement);
        } else if (action.kind == AndroidDesktopAction.Kind.PENDING_INTENT) {
            result = executePendingIntent(action, placement);
        } else if (action.request.kind
                == AndroidIntegrationRequest.Kind.ACTIVITY) {
            result = launchActivity(action.request, placement);
        } else if (action.request.kind
                == AndroidIntegrationRequest.Kind.BROADCAST) {
            mContext.sendBroadcast(action.request.intent);
            result = DesktopAutomationResult.success(
                    "broadcast sent",
                    describeExecution(
                            action.request.intent, action.request.kind));
        } else {
            final ComponentName started = action.request.foregroundService
                    ? mContext.startForegroundService(action.request.intent)
                    : mContext.startService(action.request.intent);
            result = started == null
                    ? DesktopAutomationResult.failure(
                            "Android did not resolve the requested service")
                    : DesktopAutomationResult.success(
                            "service start accepted",
                            describeExecution(
                                    action.request.intent,
                                    action.request.kind)
                                    .put("component",
                                            started.flattenToShortString())
                                    .put("foreground",
                                            action.request.foregroundService));
        }
        AndroidActivityCompatibilityHistory.record(action, result);
        DesktopAutomationEventJournal.record(
                "android-action",
                action.id,
                result.success,
                action.source,
                AndroidActivityCompatibilityHistory.diagnosticSummary(
                        action, result));
        return result;
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
                        args == null ? 100 : args.optInt("limit", 100),
                        "application",
                        BuildConfig.APPLICATION_ID));
    }

    DesktopAutomationResult listDesktopActions() throws JSONException {
        return DesktopAutomationResult.success(
                "desktop actions listed",
                new JSONObject().put(
                        "actions", AndroidDesktopActionCatalog.describe()));
    }

    DesktopAutomationResult invokeDesktopAction(final JSONObject args)
            throws IOException, JSONException {
        return execute(
                AndroidDesktopActionCatalog.create(
                        requiredString(args, "actionId"), args, "automation"),
                launchTarget(args));
    }

    DesktopAutomationResult invokeDesktopAction(
            final String actionId,
            final JSONObject args,
            final String source,
            final int displayId) throws IOException, JSONException {
        return execute(
                AndroidDesktopActionCatalog.create(
                        actionId, args, source),
                displayId);
    }

    DesktopAutomationResult getActivityCompatibilityHistory(
            final JSONObject args) throws JSONException {
        final int limit = args == null ? 64
                : Math.max(1, Math.min(64, args.optInt("limit", 64)));
        return DesktopAutomationResult.success(
                "Activity compatibility history read",
                new JSONObject().put(
                        "launches",
                        AndroidActivityCompatibilityHistory.snapshot(limit)));
    }

    DesktopAutomationResult launchIntent(final JSONObject args)
            throws IOException, JSONException {
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.ACTIVITY);
        if (request.kind != AndroidIntegrationRequest.Kind.ACTIVITY) {
            throw new IllegalArgumentException(
                    "launch_intent requires kind=activity");
        }
        return execute(
                AndroidDesktopAction.request(
                        "launch-intent", "mcp", request),
                launchTarget(args));
    }

    DesktopAutomationResult launchApplication(final AppIdentity application,
            final AppLaunchTarget target, final DesktopLaunchPresentation presentation,
            final ToolLaunchTarget placement) throws IOException, JSONException {
        AppProfile.requireCurrent(mContext, application);
        final Intent intent = target.resolve(mPackageManager);
        if (intent == null) {
            return DesktopAutomationResult.failure("launcher Activity is unavailable");
        }
        final DesktopAutomationResult result = execute(AndroidDesktopAction.request(
                "launch-app", "mcp", AndroidIntegrationRequest.activity(intent, target.packageName,
                        presentation, false, "", false)).forApplication(application), placement);
        if (result.success) {
            result.data.put("appIdentity", application.persistentKey());
        }
        return result;
    }

    DesktopAutomationResult openUri(final JSONObject args)
            throws IOException, JSONException {
        final JSONObject request = new JSONObject(args.toString());
        request.put("kind", AndroidIntegrationRequest.Kind.ACTIVITY.wireName)
                .put("action", Intent.ACTION_VIEW)
                .put("dataUri", requiredString(args, "uri"));
        request.remove("uri");
        return execute(
                AndroidDesktopAction.request(
                        "open-uri",
                        "mcp",
                        AndroidIntegrationRequest.parse(
                                request,
                                AndroidIntegrationRequest.Kind.ACTIVITY)),
                launchTarget(args));
    }

    DesktopAutomationResult openFile(final JSONObject args)
            throws IOException, JSONException {
        final String action = fileAction(args);
        final ShellFileGrantStore.Preparation grants =
                new ShellFileGrantStore.Preparation(mContext);
        final Uri uri;
        String mimeType = optionalString(args, "mimeType", "");
        final String path = optionalString(args, "path", "");
        final String uriValue = optionalString(args, "uri", "");
        if (path.isEmpty() == uriValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "provide exactly one of path or uri");
        }
        boolean writable = args.optBoolean("writable", false);
        if (!path.isEmpty()) {
            if (!ShellAccess.isReady()) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                        "shell command service is unavailable", true);
            }
            final ShellFileInfo file = ShellAccess.getShellFileInfo(path);
            final ShellFileGrantStore.Entry grant =
                    new ShellFileGrantStore.Entry(file, writable);
            uri = grants.add(grant);
            writable = grant.writable;
            if (mimeType.isEmpty()) {
                mimeType = file.mimeType;
            }
        } else {
            uri = Uri.parse(uriValue);
        }
        if (mimeType.isEmpty()) {
            mimeType = "application/octet-stream";
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
        final AndroidDesktopAction request = AndroidDesktopAction.request(
                "open-file",
                "mcp",
                AndroidIntegrationRequest.activity(
                        intent,
                        optionalString(args, "name", "Open file"),
                        AndroidIntegrationRequest.parsePresentation(
                                args,
                                args.optBoolean("chooser", false)
                                                || args.optBoolean(
                                                        "expectResult", false)
                                        ? DesktopTaskInstancePolicy.CREATE_NEW
                                        : DesktopTaskInstancePolicy.REUSE_EXISTING),
                        args.optBoolean("chooser", false),
                        optionalString(args, "chooserTitle", ""),
                        args.optBoolean("expectResult", false)));
        final ToolLaunchTarget placement = launchTarget(args);
        requireLaunchTarget(placement, request.presentation);
        grants.publish();
        // Observation may time out after dispatch; that must not revoke a consumer's URIs.
        return execute(request, placement);
    }

    DesktopAutomationResult share(final JSONObject args)
            throws IOException, JSONException {
        final List<String> files = shareFiles(args);
        final ShellFileGrantStore.Preparation grants =
                new ShellFileGrantStore.Preparation(mContext);
        final ArrayList<AndroidContentPayload.UriItem> uriItems =
                new ArrayList<>(files.size());
        for (final String value : files) {
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
                uriItems.add(new AndroidContentPayload.UriItem(
                        grants.add(new ShellFileGrantStore.Entry(file, false)),
                        file.mimeType));
            }
        }
        final AndroidContentPayload content = sharePayload(args, uriItems);
        final Intent intent = AndroidContentIntentAdapter.share(content);
        applyTarget(intent, args);
        final AndroidDesktopAction request = AndroidDesktopAction.request(
                "share",
                "mcp",
                AndroidIntegrationRequest.activity(
                        intent,
                        optionalString(args, "name", "Share"),
                        AndroidIntegrationRequest.parsePresentation(
                                args, DesktopTaskInstancePolicy.CREATE_NEW),
                        args.optBoolean("chooser", true),
                        optionalString(args, "chooserTitle", "Share with"),
                        false));
        final ToolLaunchTarget placement = launchTarget(args);
        requireLaunchTarget(placement, request.presentation);
        grants.publish();
        return execute(request, placement);
    }

    static String fileAction(final JSONObject args) {
        final String operation = optionalString(args, "operation", "view");
        switch (operation) {
            case "view":
                return Intent.ACTION_VIEW;
            case "edit":
                return Intent.ACTION_EDIT;
            default:
                throw new IllegalArgumentException("operation must be view or edit");
        }
    }

    static List<String> shareFiles(final JSONObject args) throws JSONException {
        if (args == null || args.isNull("files")) {
            return List.of();
        }
        final JSONArray files = args.getJSONArray("files");
        if (files.length() > AndroidContentPayload.MAX_URI_ITEMS) {
            throw new IllegalArgumentException(
                    "share accepts at most " + AndroidContentPayload.MAX_URI_ITEMS + " files");
        }
        final List<String> sources = new ArrayList<>(files.length());
        for (int index = 0; index < files.length(); index++) {
            final Object value = files.get(index);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException("files must contain non-empty strings");
            }
            sources.add(((String) value).trim());
        }
        return sources;
    }

    static AndroidContentPayload sharePayload(
            final JSONObject args, final List<AndroidContentPayload.UriItem> uriItems) {
        final String text = args == null ? "" : args.optString("text", "");
        if (text.isEmpty() && uriItems.isEmpty()) {
            throw new IllegalArgumentException("share requires text or files");
        }
        final String mimeType = optionalString(args, "mimeType", "");
        return AndroidContentPayload.create(
                AndroidContentPayload.Origin.APPLICATION,
                optionalString(args, "name", "Share"),
                optionalString(args, "subject", ""),
                text,
                "",
                uriItems,
                mimeType.isEmpty() ? List.of() : List.of(mimeType),
                false);
    }

    DesktopAutomationResult openContent(
            final AndroidContentPayload content,
            final int displayId) throws IOException, JSONException {
        final Intent intent = AndroidContentIntentAdapter.open(content);
        if (intent == null) {
            return DesktopAutomationResult.failure(
                    "clipboard content cannot be opened");
        }
        return execute(
                AndroidDesktopAction.request(
                        "open-content",
                        content.origin.name().toLowerCase(Locale.ROOT),
                        AndroidIntegrationRequest.activity(
                        intent,
                        content.label.isEmpty()
                                ? "Clipboard content" : content.label,
                        DesktopLaunchPresentation.automatic(),
                        false,
                        "",
                        false)),
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
        return execute(
                AndroidDesktopAction.request(
                        "share-content",
                        content.origin.name().toLowerCase(Locale.ROOT),
                        AndroidIntegrationRequest.activity(
                        intent,
                        "Share clipboard content",
                        DesktopLaunchPresentation.automatic()
                                .withInstancePolicy(
                                        DesktopTaskInstancePolicy.CREATE_NEW),
                        true,
                        "Share with",
                        false)),
                displayId);
    }

    DesktopAutomationResult deliverContent(
            final AndroidContentPayload content,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId) throws IOException, JSONException {
        if (target == null) {
            throw new IllegalArgumentException("content target is required");
        }
        final Intent intent = AndroidContentIntentAdapter.deliver(content);
        if (intent == null) {
            return DesktopAutomationResult.failure(
                    "dragged content cannot be delivered");
        }
        intent.setPackage(target.packageName);
        final AndroidIntegrationRequest request =
                AndroidIntegrationRequest.activity(
                        intent,
                        content.label.isEmpty()
                                ? "Deliver content" : content.label,
                        presentation,
                        false,
                        "",
                        false);
        return execute(
                AndroidDesktopAction.request(
                        "deliver-content",
                        content.origin.name().toLowerCase(Locale.ROOT),
                        request),
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
        try {
            return execute(
                    AndroidDesktopAction.request(
                            "send-broadcast", "mcp", request),
                    Display.DEFAULT_DISPLAY);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    DesktopAutomationResult startService(final JSONObject args)
            throws JSONException {
        final AndroidIntegrationRequest request = AndroidIntegrationRequest.parse(
                args, AndroidIntegrationRequest.Kind.SERVICE);
        if (request.kind != AndroidIntegrationRequest.Kind.SERVICE) {
            throw new IllegalArgumentException(
                    "start_service requires kind=service");
        }
        try {
            return execute(
                    AndroidDesktopAction.request(
                            "start-service", "mcp", request),
                    Display.DEFAULT_DISPLAY);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    DesktopAutomationResult listAppActions(final JSONObject args)
            throws JSONException {
        final AppIdentity application = AutomationJsonArguments.requiredApplication(mContext, args);
        final AppLaunchTarget target = AutomationJsonArguments.applicationTarget(application, args);
        final JSONArray actions = new JSONArray();
        for (final AppShortcutAction action
                : new AppShortcutRepository(mContext).loadAll(application, target)) {
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
                        .put("appIdentity", application.persistentKey())
                        .put("actions", actions));
    }

    DesktopAutomationResult invokeAppAction(final JSONObject args)
            throws IOException, JSONException {
        final AppIdentity application = AutomationJsonArguments.requiredApplication(mContext, args);
        final AppLaunchTarget target = AutomationJsonArguments.applicationTarget(application, args);
        final String actionId = requiredString(args, "actionId");
        return execute(
                AndroidDesktopAction.shortcut(
                        "app-shortcut",
                        "mcp",
                        new AndroidShortcutSpec(application, target, actionId),
                        AndroidIntegrationRequest.parsePresentation(
                                args, DesktopTaskInstancePolicy.REUSE_EXISTING)),
                launchTarget(args));
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
                    .put("userId", entry.userId)
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
                requiredString(args, "requestId"),
                Math.max(0L, Math.min(
                        60_000L, args.optLong("waitMillis", 0L))),
                args.optBoolean("consume", false));
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
        final JSONObject parameters = optionalObject(args, "parameters");
        final String encodedParameters = parameters.toString();
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

    private DesktopAutomationResult executePendingIntent(
            final AndroidDesktopAction action,
            final ToolLaunchTarget placement) throws IOException, JSONException {
        final int displayId = placement.displayId;
        final PendingIntent pendingIntent = action.pendingIntent;
        if (!pendingIntent.isActivity()) {
            try {
                pendingIntent.send();
                return DesktopAutomationResult.success(
                        "PendingIntent sent",
                        new JSONObject()
                                .put("creatorPackage", value(
                                        pendingIntent.getCreatorPackage()))
                                .put("activity", false));
            } catch (PendingIntent.CanceledException | RuntimeException error) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "PendingIntent failed: "
                                + ShellAccess.usefulMessage(error),
                        false);
            }
        }
        if (!placement.desktop) {
            ShellAccess.sendActivityOnDisplay(pendingIntent, displayId);
            return OrdinaryActivityLaunch.accepted(displayId, new JSONObject()
                    .put("creatorPackage", value(pendingIntent.getCreatorPackage()))
                    .put("activity", true));
        }
        final String creatorPackage = value(pendingIntent.getCreatorPackage());
        if (creatorPackage.isEmpty()) {
            return DesktopAutomationResult.failure(
                    "PendingIntent creator package is unavailable");
        }
        final AppLaunchTarget target = AppLaunchTarget.packageDefault(
                creatorPackage);
        final DesktopLaunchRequest request = new DesktopLaunchRequest(
                action.id,
                creatorPackage,
                AndroidLaunchSpec.pendingActivity(target, pendingIntent),
                null,
                null,
                action.presentation,
                DesktopLaunchArguments.empty(),
                "");
        final DesktopActivityLaunchResult launch =
                DesktopRuntimeBridge.launchAutomationRequestObserved(
                        request,
                        displayId,
                        LAUNCH_OBSERVE_TIMEOUT_MILLIS);
        if (!launch.succeeded() || !launch.hasObservedTask()) {
            return DesktopAutomationResult.failure(
                    launch.isDefinitiveFailure()
                            ? DesktopAutomationErrorCode.ACTION_FAILED
                            : DesktopAutomationErrorCode.TIMEOUT,
                    launch.error.isEmpty()
                            ? "Pending Activity task was not observed"
                            : launch.error,
                    !launch.isDefinitiveFailure());
        }
        final DesktopTaskLaunchObservation observation;
        try {
            observation = DesktopTaskLaunchObservation.awaitTopology(
                    action.presentation.mode,
                    displayId,
                    launch.taskId,
                    LAUNCH_OBSERVE_TIMEOUT_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TIMEOUT,
                    "Pending Activity observation was interrupted",
                    true);
        }
        if (observation.task == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    observation.error,
                    true);
        }
        final JSONObject data = new JSONObject()
                .put("creatorPackage", creatorPackage)
                .put("activity", true)
                .put("displayId", displayId)
                .put("mode", action.presentation.mode.wireName)
                .put("instance", action.presentation.instancePolicy.wireName)
                .put("taskId", observation.task.taskId)
                .put("transportTaskId", launch.taskId)
                .put("reused", launch.reused);
        describeObservedTask(data, observation.task);
        return DesktopAutomationResult.success(
                "Pending Activity launched", data);
    }

    private DesktopAutomationResult executeShortcut(
            final AndroidDesktopAction action,
            final ToolLaunchTarget placement) throws IOException, JSONException {
        final int displayId = placement.displayId;
        requireShortcutPresentation(action.presentation);
        final AndroidShortcutSpec shortcut = action.shortcut;
        AppProfile.requireCurrent(mContext, shortcut.application);
        if (!placement.desktop) {
            ShellAccess.sendActivityOnDisplay(ShellAccess.getShortcutLaunchIntent(
                    shortcut.publisher.packageName, shortcut.shortcutId), displayId);
            return OrdinaryActivityLaunch.accepted(displayId, new JSONObject()
                    .put("package", shortcut.publisher.packageName)
                    .put("appIdentity", shortcut.application.persistentKey())
                    .put("actionId", shortcut.shortcutId));
        }
        final DesktopActivityLaunchResult launch =
                DesktopRuntimeBridge.invokeAppActionObserved(
                        shortcut.application,
                        shortcut.publisher,
                        shortcut.shortcutId,
                        action.presentation,
                        displayId,
                        LAUNCH_OBSERVE_TIMEOUT_MILLIS);
        final JSONObject base = new JSONObject()
                .put("package", shortcut.publisher.packageName)
                .put("actionId", shortcut.shortcutId)
                .put("displayId", displayId)
                .put("mode", action.presentation.mode.wireName)
                .put("instance", action.presentation.instancePolicy.wireName);
        if (!launch.succeeded() || !launch.hasObservedTask()) {
            return DesktopAutomationResult.failure(
                    launch.isDefinitiveFailure()
                            ? DesktopAutomationErrorCode.ACTION_FAILED
                            : DesktopAutomationErrorCode.TIMEOUT,
                    launch.error.isEmpty()
                            ? "application shortcut task was not observed"
                            : launch.error,
                    !launch.isDefinitiveFailure(),
                    base);
        }
        final DesktopTaskLaunchObservation observation;
        try {
            observation = DesktopTaskLaunchObservation.await(
                    LaunchActivityIdentity.packageScoped(
                            FrameworkUserApi.userId(android.os.Process.myUserHandle()),
                            shortcut.publisher.packageName, null),
                    action.presentation.mode,
                    displayId,
                    launch.taskId,
                    LAUNCH_OBSERVE_TIMEOUT_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TIMEOUT,
                    "application shortcut observation was interrupted",
                    true,
                    base);
        }
        if (observation.task == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    observation.error,
                    true,
                    base.put("taskId", launch.taskId));
        }
        base.put("taskObserved", true)
                .put("taskId", observation.task.taskId)
                .put("transportTaskId", launch.taskId)
                .put("reused", launch.reused);
        describeObservedTask(base, observation.task);
        return DesktopAutomationResult.success(
                "application shortcut launched", base);
    }

    static void requireShortcutPresentation(final DesktopLaunchPresentation presentation) {
        if (presentation == null) {
            throw new IllegalArgumentException("shortcut presentation is required");
        }
        if (presentation.bounds != null
                || presentation.instancePolicy != DesktopTaskInstancePolicy.REUSE_EXISTING
                || presentation.preferredTaskId != -1) {
            throw new IllegalArgumentException(
                    "shortcuts support mode only; bounds, instance=new, and preferredTaskId are unsupported");
        }
    }

    private DesktopAutomationResult launchActivity(
            final AndroidIntegrationRequest request,
            final ToolLaunchTarget placement) throws IOException, JSONException {
        final Intent target = new Intent(request.intent);
        final AndroidActivityResolution resolution = ShellAccess.isReady()
                ? ShellAccess.resolveActivity(target)
                : AndroidActivityResolution.resolve(
                        mPackageManager,
                        target,
                        BuildConfig.APPLICATION_ID);
        final ComponentName resolvedComponent = resolution.component;
        if (!resolution.hasHandlers()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "no visible Activity handles the requested Intent",
                    false,
                    describeExecution(target, request.kind));
        }
        if (resolution.authorization != null
                && !resolution.authorization.allowed()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "Activity launch denied: "
                            + resolution.authorization.decisionName(),
                    false,
                    describeExecution(target, request.kind)
                            .put("resolvedComponent",
                                    resolution.component
                                            .flattenToShortString())
                            .put("authorization",
                                    describeAuthorization(
                                            resolution.authorization)));
        }
        final AndroidActivityLaunchPolicy launchPolicy =
                AndroidActivityLaunchPolicy.select(
                        request.chooser,
                        request.expectResult,
                        resolution.requiresResolver(),
                        resolution.authorization != null
                                && resolution.authorization
                                        .requiresAppIdentity(),
                        target.getFlags());
        if (!launchPolicy.selectionSurface || resolvedComponent != null
                && AndroidActivityResolution.isLauncherEntry(target)) {
            target.setComponent(resolvedComponent);
        }
        final String resultRequestId = request.expectResult
                ? AndroidActivityResultStore.begin(target) : "";
        try {
            final DesktopAutomationResult result = launchResolvedActivity(
                    request, placement, target, resolution, launchPolicy, resultRequestId);
            if (!resultRequestId.isEmpty()) {
                // An observation failure can still have a live result relay.
                // Return its id so the caller can wait for it or discard it.
                (result.success ? result.data : result.observation).put("requestId", resultRequestId);
            }
            return result;
        } catch (IOException | JSONException | RuntimeException failure) {
            try {
                AndroidActivityResultStore.discard(resultRequestId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private DesktopAutomationResult launchResolvedActivity(
            final AndroidIntegrationRequest request,
            final ToolLaunchTarget placement,
            final Intent target,
            final AndroidActivityResolution resolution,
            final AndroidActivityLaunchPolicy launchPolicy,
            final String resultRequestId) throws IOException, JSONException {
        final int displayId = placement.displayId;
        requireLaunchTarget(placement, request.presentation);
        final ComponentName resolvedComponent = resolution.component;
        final Intent launchedIntent;
        final String relayId;
        final AndroidLaunchSpec.Delivery delivery;
        if (launchPolicy.usesResultRelay()) {
            // Result delivery needs a real Activity lifecycle owner. Shell
            // places only this app-owned host; its nested target never crosses
            // the Binder boundary.
            relayId = AndroidActivityRelayStore.put(
                    target, request.chooser, request.chooserTitle);
            launchedIntent = AndroidActivityRelayActivity.createIntent(
                    mContext, relayId, resultRequestId);
            delivery = AndroidLaunchSpec.Delivery.SHELL_INTENT;
        } else {
            relayId = "";
            launchedIntent = request.chooser
                    ? Intent.createChooser(
                            target,
                            request.chooserTitle.isEmpty()
                                    ? null : request.chooserTitle)
                    : target;
            // The app authorizes the target and grants; shell later sends this
            // one-shot token with only task-placement options.
            delivery = launchPolicy.delivery
                    == AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT
                            ? AndroidLaunchSpec.Delivery.APP_PENDING_INTENT
                            : AndroidLaunchSpec.Delivery.SHELL_INTENT;
        }
        final ComponentName component = launchComponent(
                launchedIntent,
                launchPolicy.usesResultRelay()
                        ? launchedIntent.getComponent()
                        : resolvedComponent);
        if (component == null) {
            AndroidActivityRelayStore.discard(relayId);
            if (!resultRequestId.isEmpty()) {
                AndroidActivityResultStore.fail(
                        resultRequestId,
                        new IllegalStateException(
                                "Activity launch surface could not be resolved"));
            }
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "Activity launch surface could not be resolved",
                    false,
                    describeExecution(target, request.kind));
        }
        if (!placement.desktop) {
            try {
                OrdinaryActivityLaunch.launch(mContext,
                        request.presentation.instancePolicy.applyTo(launchedIntent),
                        delivery, displayId);
            } catch (IOException | RuntimeException error) {
                AndroidActivityRelayStore.discard(relayId);
                throw error;
            }
            return OrdinaryActivityLaunch.accepted(displayId,
                    describeActivityLaunch(request, target, resolution, launchPolicy));
        }
        final AppLaunchTarget transportTarget = AppLaunchTarget.explicit(
                component.getPackageName(),
                component.getClassName(),
                launchedIntent.getAction());
        final AppLaunchTarget taskTarget = request.presentation.preferredTaskId > 0
                || (launchPolicy.selectionSurface
                        && !launchPolicy.usesResultRelay())
                ? AppLaunchTarget.packageDefault(component.getPackageName())
                : transportTarget;
        final DesktopLaunchRequest desktopRequest = new DesktopLaunchRequest(
                request.name,
                taskTarget.packageName,
                AndroidLaunchSpec.intent(
                        taskTarget,
                        request.presentation.instancePolicy.applyTo(
                                launchedIntent),
                        delivery),
                null,
                null,
                request.presentation,
                DesktopLaunchArguments.empty(),
                "");
        final DesktopActivityLaunchResult result =
                DesktopRuntimeBridge.launchAutomationRequestObserved(
                        desktopRequest,
                        displayId,
                        LAUNCH_OBSERVE_TIMEOUT_MILLIS);
        if (!result.succeeded()) {
            if (result.isDefinitiveFailure()) {
                AndroidActivityRelayStore.discard(relayId);
                if (!resultRequestId.isEmpty()) {
                    AndroidActivityResultStore.fail(
                            resultRequestId,
                            new IllegalStateException(result.error));
                }
            }
            return DesktopAutomationResult.failure(
                    result.isDefinitiveFailure()
                            ? DesktopAutomationErrorCode.ACTION_FAILED
                            : DesktopAutomationErrorCode.TIMEOUT,
                    result.error,
                    !result.isDefinitiveFailure(),
                    describeExecution(target, request.kind));
        }
        if (!result.hasObservedTask()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "desktop launch was accepted but no task was observed",
                    true,
                    describeExecution(target, request.kind));
        }
        final DesktopTaskLaunchObservation observation;
        try {
            if (launchPolicy.selectionSurface) {
                observation = DesktopTaskLaunchObservation
                        .awaitTopology(
                                request.presentation.mode,
                                displayId,
                                result.taskId,
                                LAUNCH_OBSERVE_TIMEOUT_MILLIS);
            } else {
                observation = DesktopTaskLaunchObservation.await(
                        LaunchActivityIdentity.resolve(
                                FrameworkUserApi.userId(android.os.Process.myUserHandle()),
                                mPackageManager, taskTarget),
                        request.presentation.mode,
                        displayId,
                        result.taskId,
                        LAUNCH_OBSERVE_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TIMEOUT,
                    "Activity launch observation was interrupted",
                    true,
                    describeExecution(target, request.kind));
        }
        if (observation.task == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    observation.error,
                    true,
                    describeExecution(target, request.kind)
                            .put("taskId", result.taskId));
        }
        final JSONObject data = describeActivityLaunch(request, target, resolution, launchPolicy)
                .put("displayId", displayId)
                .put("placement", "desktop")
                .put("taskObserved", true)
                .put("taskId", observation.task.taskId)
                .put("transportTaskId", result.taskId)
                .put("reused", result.reused);
        describeObservedTask(data, observation.task);
        return DesktopAutomationResult.success(
                "Android Activity launched", data);
    }

    private static JSONObject describeActivityLaunch(final AndroidIntegrationRequest request,
            final Intent target, final AndroidActivityResolution resolution,
            final AndroidActivityLaunchPolicy policy) throws JSONException {
        return describeExecution(target, request.kind)
                .put("mode", request.presentation.mode.wireName)
                .put("instance", request.presentation.instancePolicy.wireName)
                .put("resolvedComponent", resolution.component == null
                        ? "" : resolution.component.flattenToShortString())
                .put("resolution", resolution.stateName())
                .put("handlerCount", resolution.handlerCount)
                .put("authorization", describeAuthorization(resolution.authorization))
                .put("launchIdentity", policy.identityName())
                .put("delivery", policy.deliveryName())
                .put("relay", policy.usesResultRelay())
                .put("resultExpected", request.expectResult);
    }

    private ComponentName launchComponent(
            final Intent intent,
            final ComponentName fallback) {
        if (intent.getComponent() != null) {
            return intent.getComponent();
        }
        final ResolveInfo resolved = mPackageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null) {
            return new ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name);
        }
        return fallback;
    }

    private int optionalDisplayId(final JSONObject args) {
        return launchTarget(args).displayId;
    }

    ToolLaunchTarget launchTarget(final JSONObject args) {
        return ToolLaunchTarget.resolve(optionalString(args, "placement", "auto"),
                args != null && args.has("displayId") ? requiredInt(args, "displayId") : -1,
                DesktopRuntimeBridge.getActiveDesktopDisplayId());
    }

    private void requireLaunchTarget(final ToolLaunchTarget placement,
            final DesktopLaunchPresentation presentation) throws IOException {
        placement.requireCurrent(DesktopRuntimeBridge.getActiveDesktopDisplayId());
        if (!placement.desktop) {
            OrdinaryActivityLaunch.requirePresentation(presentation);
        }
        if (placement.displayId != Display.DEFAULT_DISPLAY) {
            DesktopDisplayCatalog.require(placement.displayId, null);
        }
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

    private static JSONObject describeAuthorization(
            final AndroidActivityAuthorization authorization)
            throws JSONException {
        if (authorization == null) {
            return new JSONObject().put("decision", "system-resolver");
        }
        return new JSONObject()
                .put("decision", authorization.decisionName())
                .put("exported", authorization.exported)
                .put("enabled", authorization.enabled)
                .put("requiredPermission", authorization.requiredPermission)
                .put("permissionGranted", authorization.permissionGranted)
                .put("samePackage", authorization.samePackage);
    }

    private static void describeObservedTask(
            final JSONObject data,
            final TaskRepository.TaskEntry task) throws JSONException {
        data.put("observedComponent", task.componentName)
                .put("observedTopActivity", task.topActivityName)
                .put("observedActivityType", task.activityType)
                .put("observedMode", DesktopLaunchMode.semanticWindowingMode(
                        task.windowingMode))
                .put("nativeWindowingMode", task.windowingMode)
                .put("bounds", new JSONObject()
                        .put("left", task.bounds.left)
                        .put("top", task.bounds.top)
                        .put("right", task.bounds.right)
                        .put("bottom", task.bounds.bottom));
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


    static JSONObject optionalObject(final JSONObject args, final String name) {
        if (args == null || !args.has(name)) {
            return new JSONObject();
        }
        final Object value = args.opt(name);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (JSONObject) value;
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
