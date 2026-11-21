package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Method;

/** Optional per-display IME policy; resolved only when placement is requested. */
final class FrameworkDisplayImeApi {
    private final Object mWindowManager;
    private final Method mGet;
    private final Method mSet;

    FrameworkDisplayImeApi() throws ReflectiveOperationException {
        final Class<?> interfaceType = Class.forName("android.view.IWindowManager");
        mWindowManager = FrameworkWindowServiceApi.get();
        mGet = interfaceType.getMethod("getDisplayImePolicy", int.class);
        mSet = interfaceType.getMethod("setDisplayImePolicy", int.class, int.class);
    }

    int get(final int displayId) throws ReflectiveOperationException {
        return (Integer) mGet.invoke(mWindowManager, displayId);
    }

    void set(final int displayId, final int policy) throws ReflectiveOperationException {
        mSet.invoke(mWindowManager, displayId, policy);
    }
}
