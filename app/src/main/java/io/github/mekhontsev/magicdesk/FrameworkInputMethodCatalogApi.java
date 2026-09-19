package io.github.mekhontsev.magicdesk;

import android.view.inputmethod.InputMethodInfo;
import java.lang.reflect.Method;
import java.util.List;

/** Release-dependent IME list representation; permission failures are not fallbacks. */
final class FrameworkInputMethodCatalogApi {
    private FrameworkInputMethodCatalogApi() { }

    @SuppressWarnings("unchecked")
    static List<InputMethodInfo> enabled(Class<?> api, Object service, int userId)
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
}
