package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Shared resolver and descriptor for app-visible and shell-visible Intent queries. */
final class AndroidIntentHandlerQuery {
    private AndroidIntentHandlerQuery() {
    }

    static JSONObject query(
            final PackageManager packageManager,
            final AndroidIntegrationRequest request,
            final int requestedLimit,
            final String visibilityScope) throws JSONException {
        final List<ResolveInfo> resolved;
        switch (request.kind) {
            case ACTIVITY:
                resolved = packageManager.queryIntentActivities(
                        request.intent, PackageManager.MATCH_DEFAULT_ONLY);
                break;
            case BROADCAST:
                resolved = packageManager.queryBroadcastReceivers(
                        request.intent, 0);
                break;
            case SERVICE:
                resolved = packageManager.queryIntentServices(
                        request.intent, 0);
                break;
            default:
                throw new IllegalArgumentException("unsupported Android target kind");
        }
        final List<ResolveInfo> sorted = new ArrayList<>(resolved);
        Collections.sort(sorted, Comparator
                .comparingInt((ResolveInfo info) -> info.priority).reversed()
                .thenComparing(info -> componentName(info, request.kind)));
        final int limit = Math.max(1, Math.min(200, requestedLimit));
        final JSONArray handlers = new JSONArray();
        for (int index = 0;
                index < sorted.size() && index < limit;
                index++) {
            handlers.put(describeHandler(
                    packageManager, sorted.get(index), request.kind));
        }
        return new JSONObject()
                .put("kind", request.kind.wireName)
                .put("visibilityScope", visibilityScope)
                .put("count", handlers.length())
                .put("truncated", sorted.size() > handlers.length())
                .put("handlers", handlers);
    }

    private static JSONObject describeHandler(
            final PackageManager packageManager,
            final ResolveInfo info,
            final AndroidIntegrationRequest.Kind kind) throws JSONException {
        final String packageName;
        final boolean exported;
        final boolean enabled;
        final String permission;
        if (kind == AndroidIntegrationRequest.Kind.SERVICE) {
            final ServiceInfo service = info.serviceInfo;
            packageName = service == null ? "" : service.packageName;
            exported = service != null && service.exported;
            enabled = service != null && service.enabled;
            permission = service == null ? "" : value(service.permission);
        } else {
            final ActivityInfo activity = info.activityInfo;
            packageName = activity == null ? "" : activity.packageName;
            exported = activity != null && activity.exported;
            enabled = activity != null && activity.enabled;
            permission = activity == null ? "" : value(activity.permission);
        }
        final CharSequence label = info.loadLabel(packageManager);
        return new JSONObject()
                .put("component", componentName(info, kind))
                .put("package", packageName)
                .put("label", label == null ? "" : label.toString())
                .put("exported", exported)
                .put("enabled", enabled)
                .put("permission", permission)
                .put("priority", info.priority)
                .put("preferredOrder", info.preferredOrder)
                .put("isDefault", info.isDefault);
    }

    private static String componentName(
            final ResolveInfo info,
            final AndroidIntegrationRequest.Kind kind) {
        if (kind == AndroidIntegrationRequest.Kind.SERVICE) {
            return info.serviceInfo == null ? ""
                    : new ComponentName(
                            info.serviceInfo.packageName,
                            info.serviceInfo.name).flattenToShortString();
        }
        return info.activityInfo == null ? ""
                : new ComponentName(
                        info.activityInfo.packageName,
                        info.activityInfo.name).flattenToShortString();
    }

    private static String value(final String value) {
        return value == null ? "" : value;
    }
}
