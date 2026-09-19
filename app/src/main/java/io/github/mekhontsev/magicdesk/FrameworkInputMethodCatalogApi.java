package io.github.mekhontsev.magicdesk;

import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.List;

/** Privileged IME catalog and selection primitives, independent of layout policy. */
final class FrameworkInputMethodCatalogApi {
    private static final String INPUT_METHOD_SUBTYPE_SAFE_LIST =
            "com.android.internal.inputmethod.InputMethodSubtypeSafeList";
    private final Class<?> mApi;
    private final Object mService;

    FrameworkInputMethodCatalogApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input_method");
        if (binder == null) throw new IllegalStateException("input method service is unavailable");
        mApi = Class.forName("com.android.internal.view.IInputMethodManager");
        mService = Class.forName("com.android.internal.view.IInputMethodManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    InputMethodInfo currentInputMethod(int userId) throws ReflectiveOperationException {
        return (InputMethodInfo) mApi.getMethod("getCurrentInputMethodInfoAsUser", int.class)
                .invoke(mService, userId);
    }

    InputMethodSubtype currentSubtype(int userId) throws ReflectiveOperationException {
        return (InputMethodSubtype) mApi.getMethod("getCurrentInputMethodSubtype", int.class)
                .invoke(mService, userId);
    }

    List<InputMethodInfo> enabled(int userId) throws ReflectiveOperationException {
        return enabled(mApi, mService, userId);
    }

    List<InputMethodSubtype> enabledSubtypes(String imeId, int userId)
            throws ReflectiveOperationException {
        return extractEnabledInputMethodSubtypes(mApi.getMethod("getEnabledInputMethodSubtypeList",
                String.class, boolean.class, int.class).invoke(mService, imeId, true, userId));
    }

    void switchSubtype(int displayId) throws ReflectiveOperationException {
        mApi.getMethod("onImeSwitchButtonClickFromSystem", int.class).invoke(mService, displayId);
    }

    @SuppressWarnings("unchecked")
    private static List<InputMethodInfo> enabled(Class<?> api, Object service, int userId)
            throws ReflectiveOperationException {
        final Method method;
        try { method = api.getMethod("getEnabledInputMethodList", int.class); }
        catch (NoSuchMethodException absent) {
            return (List<InputMethodInfo>) api.getMethod("getEnabledInputMethodListLegacy", int.class)
                    .invoke(service, userId);
        }
        final Object result = method.invoke(service, userId);
        if (result instanceof List<?>) return (List<InputMethodInfo>) result;
        if (result == null) throw new IllegalStateException("Enabled IME list is unavailable");
        return (List<InputMethodInfo>) result.getClass().getMethod("extractFrom", result.getClass())
                .invoke(null, result);
    }

    @SuppressWarnings("unchecked")
    static List<InputMethodSubtype> extractEnabledInputMethodSubtypes(Object result)
            throws ReflectiveOperationException {
        if (result == null) return List.of();
        if (result instanceof List<?>) return (List<InputMethodSubtype>) result;
        final Class<?> resultClass = result.getClass();
        if (!INPUT_METHOD_SUBTYPE_SAFE_LIST.equals(resultClass.getName())) {
            throw new IllegalStateException("unsupported enabled subtype result: " + resultClass.getName());
        }
        final Object extracted = resultClass.getMethod("extractFrom", resultClass).invoke(null, result);
        if (!(extracted instanceof List<?>)) {
            throw new IllegalStateException("extracted subtype result is not a List");
        }
        return (List<InputMethodSubtype>) extracted;
    }
}
