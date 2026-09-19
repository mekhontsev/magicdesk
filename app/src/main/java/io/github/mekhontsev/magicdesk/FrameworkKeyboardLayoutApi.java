package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.LocaleList;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Hidden physical-keyboard layout contracts; callers only see public Android types. */
final class FrameworkKeyboardLayoutApi {
    private final Object mService;
    private final Class<?> mApi;
    private final Class<?> mIdentifierClass;
    private final Class<?> mLayoutClass;

    FrameworkKeyboardLayoutApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        if (binder == null) throw new IllegalStateException("input service is unavailable");
        mApi = Class.forName("android.hardware.input.IInputManager");
        mService = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        mIdentifierClass = Class.forName("android.hardware.input.InputDeviceIdentifier");
        mLayoutClass = Class.forName("android.hardware.input.KeyboardLayout");
    }

    List<ResolvedLayout> layouts(InputDevice keyboard, int userId, InputMethodInfo ime,
            InputMethodSubtype subtype) throws ReflectiveOperationException {
        final Method descriptor = mLayoutClass.getMethod("getDescriptor");
        final Method label = mLayoutClass.getMethod("getLabel");
        final Method locales = mLayoutClass.getMethod("getLocales");
        final Method layoutType = mLayoutClass.getMethod("getLayoutType");
        final Object candidates = mApi.getMethod("getKeyboardLayoutListForInputDevice",
                mIdentifierClass, int.class, InputMethodInfo.class, InputMethodSubtype.class)
                .invoke(mService, identifier(keyboard), userId, ime, subtype);
        final List<ResolvedLayout> result = new ArrayList<>();
        for (int i = 0; candidates != null && i < Array.getLength(candidates); i++) {
            final Object candidate = Array.get(candidates, i);
            result.add(new ResolvedLayout((String) descriptor.invoke(candidate),
                    (String) label.invoke(candidate), (LocaleList) locales.invoke(candidate),
                    (String) layoutType.invoke(candidate)));
        }
        return result;
    }

    String selectedDescriptor(InputDevice keyboard, int userId, InputMethodInfo ime,
            InputMethodSubtype subtype) throws ReflectiveOperationException {
        final Object selection = mApi.getMethod("getKeyboardLayoutForInputDevice",
                mIdentifierClass, int.class, InputMethodInfo.class, InputMethodSubtype.class)
                .invoke(mService, identifier(keyboard), userId, ime, subtype);
        return selection == null ? null
                : (String) selection.getClass().getMethod("getLayoutDescriptor").invoke(selection);
    }

    void apply(InputDevice keyboard, int userId, InputMethodInfo ime,
            InputMethodSubtype subtype, String descriptor) throws ReflectiveOperationException {
        final Method override = mApi.getMethod("setKeyboardLayoutOverrideForInputDevice",
                mIdentifierClass, String.class);
        final Method set = mApi.getMethod("setKeyboardLayoutForInputDevice", mIdentifierClass,
                int.class, InputMethodInfo.class, InputMethodSubtype.class, String.class);
        if (descriptor.equals(selectedDescriptor(keyboard, userId, ime, subtype))) return;
        final Object identifier = identifier(keyboard);
        override.invoke(mService, identifier, descriptor);
        set.invoke(mService, identifier, userId, ime, subtype, descriptor);
        if (!descriptor.equals(selectedDescriptor(keyboard, userId, ime, subtype))) {
            throw new IllegalStateException("keyboard layout did not change to " + descriptor);
        }
    }

    private static Object identifier(InputDevice keyboard) throws ReflectiveOperationException {
        return InputDevice.class.getMethod("getIdentifier").invoke(keyboard);
    }

    static final class ResolvedLayout {
        final String descriptor;
        final String label;
        final LocaleList locales;
        final String layoutType;

        ResolvedLayout(String descriptor, String label, LocaleList locales, String layoutType) {
            this.descriptor = descriptor;
            this.label = label;
            this.locales = locales == null ? LocaleList.getEmptyLocaleList() : locales;
            this.layoutType = layoutType;
        }
    }
}
