package io.github.mekhontsev.magicdesk;

import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.AppFunctionMetadata;
import android.app.appfunctions.AppFunctionName;
import android.app.appfunctions.AppFunctionSearchSpec;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appsearch.GenericDocument;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Shell-identity adapter for Android App Functions discovery and execution. */
final class ShellAppFunctionGateway {
    private static final long MAX_TIMEOUT_MILLIS = 60_000L;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final int MAX_DOCUMENT_DEPTH = 8;
    private static final int MAX_DOCUMENT_PROPERTIES = 128;
    private static final int MAX_ARRAY_VALUES = 256;
    private static final int MAX_STRING_CHARS = 65_536;

    private ShellAppFunctionGateway() {
    }

    static String execute(
            final Context context,
            final String packageName,
            final String functionIdentifier,
            final String parametersJson,
            final long timeoutMillis) {
        if (Build.VERSION.SDK_INT < 36) {
            return failure("Android App Functions require Android 16");
        }
        return Api36.execute(
                context,
                packageName,
                functionIdentifier,
                parametersJson,
                boundedTimeout(timeoutMillis));
    }

    static String search(
            final Context context,
            final String searchJson,
            final long timeoutMillis) {
        if (Build.VERSION.SDK_INT < 37) {
            return failure(
                    "App Function discovery requires the Android API 37 search service");
        }
        return Api37.search(
                context, searchJson, boundedTimeout(timeoutMillis));
    }

    private static long boundedTimeout(final long value) {
        return Math.max(1_000L, Math.min(
                MAX_TIMEOUT_MILLIS, value <= 0L ? 20_000L : value));
    }

    private static String success(final JSONObject data) {
        try {
            return new JSONObject()
                    .put("success", true)
                    .put("message", "ok")
                    .put("data", data == null ? new JSONObject() : data)
                    .toString();
        } catch (JSONException error) {
            return failure(error.getMessage());
        }
    }

    private static String failure(final String message) {
        try {
            return new JSONObject()
                    .put("success", false)
                    .put("message", message == null ? "" : message)
                    .put("data", new JSONObject())
                    .toString();
        } catch (JSONException impossible) {
            return "{\"success\":false,\"message\":\"serialization failed\"}";
        }
    }

    @RequiresApi(36)
    private static final class Api36 {
        private Api36() {
        }

        static String execute(
                final Context context,
                final String packageName,
                final String functionIdentifier,
                final String parametersJson,
                final long timeoutMillis) {
            if (packageName == null || packageName.trim().isEmpty()
                    || functionIdentifier == null
                    || functionIdentifier.trim().isEmpty()) {
                return failure("package and functionId are required");
            }
            final CancellationSignal cancellation = new CancellationSignal();
            try {
                final AppFunctionManager manager = ShellIdentityContext.create(context)
                        .getSystemService(AppFunctionManager.class);
                if (manager == null) {
                    return failure("Android App Function service is unavailable");
                }
                final JSONObject input = parametersJson == null
                        || parametersJson.trim().isEmpty()
                        ? new JSONObject() : new JSONObject(parametersJson);
                final GenericDocument parameters = document(
                        input,
                        input.optString("schemaType", "AppFunctionParameters"),
                        input.optJSONObject("properties") == null
                                ? input : input.getJSONObject("properties"),
                        0);
                final ExecuteAppFunctionRequest request =
                        new ExecuteAppFunctionRequest.Builder(
                                packageName.trim(), functionIdentifier.trim())
                                .setParameters(parameters)
                                .build();
                final CountDownLatch completed = new CountDownLatch(1);
                final AtomicReference<ExecuteAppFunctionResponse> response =
                        new AtomicReference<>();
                final AtomicReference<AppFunctionException> failure =
                        new AtomicReference<>();
                manager.executeAppFunction(
                        request,
                        Runnable::run,
                        cancellation,
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
                if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    cancellation.cancel();
                    return failure("App Function execution timed out");
                }
                final AppFunctionException error = failure.get();
                if (error != null) {
                    return failure("code=" + error.getErrorCode()
                            + " " + error.getErrorMessage());
                }
                final ExecuteAppFunctionResponse result = response.get();
                final GenericDocument resultDocument = result == null
                        ? null : result.getResultDocument();
                final JSONObject data = new JSONObject()
                        .put("package", packageName.trim())
                        .put("functionId", functionIdentifier.trim())
                        .put("result", toJson(resultDocument));
                if (resultDocument != null) {
                    final String value = resultDocument.getPropertyString(
                            ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE);
                    if (value != null) {
                        try {
                            data.put("returnValue", new JSONObject(value));
                        } catch (JSONException ignored) {
                            data.put("returnValue", value);
                        }
                    }
                }
                return success(data);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cancellation.cancel();
                return failure("App Function execution was interrupted");
            } catch (JSONException | PackageManager.NameNotFoundException
                    | RuntimeException error) {
                return failure(ShellAccess.usefulMessage(error));
            }
        }

        private static GenericDocument document(
                final JSONObject source,
                final String schemaType,
                final JSONObject properties,
                final int depth) throws JSONException {
            if (depth > MAX_DOCUMENT_DEPTH
                    || properties.length() > MAX_DOCUMENT_PROPERTIES) {
                throw new IllegalArgumentException(
                        "App Function parameters are too deeply nested or too large");
            }
            final GenericDocument.Builder<?> builder =
                    new GenericDocument.Builder<>(
                            boundedString(source.optString(
                                    "namespace", "magicdesk-mcp")),
                            boundedString(source.optString(
                                    "id", UUID.randomUUID().toString())),
                            boundedString(schemaType == null
                                    || schemaType.isEmpty()
                                    ? "AppFunctionParameters" : schemaType));
            final java.util.Iterator<String> names = properties.keys();
            while (names.hasNext()) {
                final String name = names.next();
                if ("namespace".equals(name)
                        || "id".equals(name)
                        || "schemaType".equals(name)
                        || "properties".equals(name)) {
                    continue;
                }
                setProperty(
                        builder,
                        boundedString(name),
                        properties.get(name),
                        depth);
            }
            return builder.build();
        }

        private static void setProperty(
                final GenericDocument.Builder<?> builder,
                final String name,
                final Object value,
                final int depth) throws JSONException {
            if (value == null || value == JSONObject.NULL) {
                builder.setPropertyString(name, new String[0]);
            } else if (value instanceof Boolean) {
                builder.setPropertyBoolean(name, (Boolean) value);
            } else if (value instanceof Integer || value instanceof Long) {
                builder.setPropertyLong(name, ((Number) value).longValue());
            } else if (value instanceof Number) {
                builder.setPropertyDouble(name, ((Number) value).doubleValue());
            } else if (value instanceof String) {
                builder.setPropertyString(name, boundedString((String) value));
            } else if (value instanceof JSONObject) {
                final JSONObject object = (JSONObject) value;
                builder.setPropertyDocument(name, document(
                        object,
                        object.optString("schemaType", "NestedDocument"),
                        object.optJSONObject("properties") == null
                                ? object : object.getJSONObject("properties"),
                        depth + 1));
            } else if (value instanceof JSONArray) {
                setArrayProperty(
                        builder, name, (JSONArray) value, depth);
            } else {
                throw new IllegalArgumentException(
                        "unsupported App Function property: " + name);
            }
        }

        private static void setArrayProperty(
                final GenericDocument.Builder<?> builder,
                final String name,
                final JSONArray values,
                final int depth) throws JSONException {
            if (values.length() > MAX_ARRAY_VALUES) {
                throw new IllegalArgumentException(
                        "App Function property array is too large: " + name);
            }
            if (values.length() == 0) {
                builder.setPropertyString(name, new String[0]);
                return;
            }
            final Object first = values.get(0);
            if (first instanceof Boolean) {
                final boolean[] result = new boolean[values.length()];
                for (int index = 0; index < result.length; index++) {
                    result[index] = values.getBoolean(index);
                }
                builder.setPropertyBoolean(name, result);
            } else if (first instanceof Integer || first instanceof Long) {
                final long[] result = new long[values.length()];
                for (int index = 0; index < result.length; index++) {
                    result[index] = values.getLong(index);
                }
                builder.setPropertyLong(name, result);
            } else if (first instanceof Number) {
                final double[] result = new double[values.length()];
                for (int index = 0; index < result.length; index++) {
                    result[index] = values.getDouble(index);
                }
                builder.setPropertyDouble(name, result);
            } else if (first instanceof JSONObject) {
                final GenericDocument[] result =
                        new GenericDocument[values.length()];
                for (int index = 0; index < result.length; index++) {
                    final JSONObject object = values.getJSONObject(index);
                    result[index] = document(
                            object,
                            object.optString("schemaType", "NestedDocument"),
                            object.optJSONObject("properties") == null
                                    ? object : object.getJSONObject("properties"),
                            depth + 1);
                }
                builder.setPropertyDocument(name, result);
            } else {
                final String[] result = new String[values.length()];
                for (int index = 0; index < result.length; index++) {
                    result[index] = boundedString(values.getString(index));
                }
                builder.setPropertyString(name, result);
            }
        }

        private static String boundedString(final String value) {
            final String text = value == null ? "" : value;
            if (text.length() > MAX_STRING_CHARS) {
                throw new IllegalArgumentException(
                        "App Function string value is too large");
            }
            return text;
        }

        private static JSONObject toJson(final GenericDocument document)
                throws JSONException {
            final JSONObject result = new JSONObject();
            if (document == null) {
                return result;
            }
            result.put("namespace", document.getNamespace())
                    .put("id", document.getId())
                    .put("schemaType", document.getSchemaType());
            final JSONObject properties = new JSONObject();
            for (final String name : document.getPropertyNames()) {
                properties.put(name, propertyToJson(document.getProperty(name)));
            }
            return result.put("properties", properties);
        }

        private static Object propertyToJson(final Object value)
                throws JSONException {
            if (value == null) {
                return JSONObject.NULL;
            }
            final JSONArray result = new JSONArray();
            if (value instanceof String[]) {
                for (final String item : (String[]) value) {
                    result.put(item);
                }
            } else if (value instanceof long[]) {
                for (final long item : (long[]) value) {
                    result.put(item);
                }
            } else if (value instanceof double[]) {
                for (final double item : (double[]) value) {
                    result.put(item);
                }
            } else if (value instanceof boolean[]) {
                for (final boolean item : (boolean[]) value) {
                    result.put(item);
                }
            } else if (value instanceof GenericDocument[]) {
                for (final GenericDocument item : (GenericDocument[]) value) {
                    result.put(toJson(item));
                }
            } else {
                return value.toString();
            }
            return result;
        }
    }

    @RequiresApi(37)
    private static final class Api37 {
        private Api37() {
        }

        static String search(
                final Context context,
                final String searchJson,
                final long timeoutMillis) {
            try {
                final AppFunctionManager manager = ShellIdentityContext.create(context)
                        .getSystemService(AppFunctionManager.class);
                if (manager == null) {
                    return failure("Android App Function service is unavailable");
                }
                final JSONObject input = searchJson == null
                        || searchJson.trim().isEmpty()
                        ? new JSONObject() : new JSONObject(searchJson);
                final AppFunctionSearchSpec.Builder builder =
                        new AppFunctionSearchSpec.Builder();
                final String packageName = input.optString("package", "").trim();
                if (!packageName.isEmpty()) {
                    builder.setPackageNames(Collections.singleton(packageName));
                }
                final String functionId = input.optString("functionId", "").trim();
                if (!functionId.isEmpty() && !packageName.isEmpty()) {
                    builder.setFunctionNames(Collections.singleton(
                            new AppFunctionName(packageName, functionId)));
                }
                final String schemaCategory = input.optString(
                        "schemaCategory", "").trim();
                if (!schemaCategory.isEmpty()) {
                    builder.setSchemaCategory(schemaCategory);
                }
                final String schemaName = input.optString(
                        "schemaName", "").trim();
                if (!schemaName.isEmpty()) {
                    builder.setSchemaName(schemaName);
                }
                if (input.has("minSchemaVersion")) {
                    builder.setMinSchemaVersion(input.getLong("minSchemaVersion"));
                }
                final CountDownLatch completed = new CountDownLatch(1);
                final AtomicReference<List<AppFunctionMetadata>> response =
                        new AtomicReference<>();
                final AtomicReference<Exception> failure = new AtomicReference<>();
                manager.searchAppFunctions(
                        builder.build(),
                        Runnable::run,
                        new OutcomeReceiver<List<AppFunctionMetadata>, Exception>() {
                            @Override
                            public void onResult(
                                    final List<AppFunctionMetadata> result) {
                                response.set(result);
                                completed.countDown();
                            }

                            @Override
                            public void onError(final Exception error) {
                                failure.set(error);
                                completed.countDown();
                            }
                        });
                if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    return failure("App Function discovery timed out");
                }
                if (failure.get() != null) {
                    return failure(ShellAccess.usefulMessage(failure.get()));
                }
                final JSONArray functions = new JSONArray();
                final List<AppFunctionMetadata> metadata = response.get();
                if (metadata != null) {
                    for (final AppFunctionMetadata item : metadata) {
                        if (functions.length() >= MAX_SEARCH_RESULTS) {
                            break;
                        }
                        final AppFunctionName name = item.getName();
                        final JSONObject function = new JSONObject()
                                .put("package", name.getPackageName())
                                .put("functionId", name.getFunctionIdentifier())
                                .put("scope", item.getScope());
                        if (item.getSchemaMetadata() != null) {
                            function.put("schemaCategory",
                                            item.getSchemaMetadata().getCategory())
                                    .put("schemaName",
                                            item.getSchemaMetadata().getName())
                                    .put("schemaVersion",
                                            item.getSchemaMetadata().getVersion());
                        }
                        functions.put(function);
                    }
                }
                return success(new JSONObject()
                        .put("functions", functions)
                        .put("count", functions.length())
                        .put("truncated", metadata != null
                                && metadata.size() > functions.length()));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return failure("App Function discovery was interrupted");
            } catch (JSONException | PackageManager.NameNotFoundException
                    | RuntimeException error) {
                return failure(ShellAccess.usefulMessage(error));
            }
        }
    }
}
