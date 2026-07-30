package io.github.mekhontsev.magicdesk;

import android.os.LocaleList;
import android.util.Base64;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HardwareKeyboardLayoutCommand {
    private static final String INPUT_METHOD_SERVICE = "input_method";
    private static final String KEYBOARD_SUBTYPE_MODE = "keyboard";
    private static final String LEGACY_ENGLISH_SUFFIX = "/keyboard_layout_english_us";
    private static final String LEGACY_RUSSIAN_SUFFIX = "/keyboard_layout_russian";

    private HardwareKeyboardLayoutCommand() {
    }

    public static void main(final String[] args) {
        if ((args.length < 1 || args.length > 3)
                || !("next".equals(args[0]) || "sync".equals(args[0]))) {
            System.err.println(
                    "usage: HardwareKeyboardLayoutCommand <next|sync>"
                            + " [current-descriptor] [layouts64]");
            System.exit(64);
            return;
        }

        try {
            final List<InputDevice> keyboards = getExternalAlphabeticKeyboards();
            if (keyboards.isEmpty()) {
                System.err.println("no external alphabetic keyboard found");
                System.exit(2);
                return;
            }

            final Object inputManager = getInputManagerService();
            final Class<?> inputManagerInterface =
                    Class.forName("android.hardware.input.IInputManager");
            final Class<?> keyboardLayoutClass =
                    Class.forName("android.hardware.input.KeyboardLayout");
            final Method getKeyboardLayout = inputManagerInterface.getMethod(
                    "getKeyboardLayout", String.class);
            final ImeState imeState = getImeState();
            final List<LayoutInfo> discoveredLayouts = resolveConfiguredLayouts(
                    inputManager, inputManagerInterface, getKeyboardLayout,
                    keyboardLayoutClass, keyboards.get(0), imeState);
            final List<LayoutInfo> persistedLayouts = args.length >= 3
                    ? resolvePersistedLayouts(
                            args[2], inputManager, getKeyboardLayout,
                            keyboardLayoutClass)
                    : new ArrayList<>();
            final List<LayoutInfo> layouts;
            // Android may switch the active IME before this command runs. Keep
            // cycling the complete list captured before that subtype race.
            if (!persistedLayouts.isEmpty()
                    && ("next".equals(args[0])
                            || discoveredLayouts.size() < 2)) {
                layouts = persistedLayouts;
            } else {
                layouts = discoveredLayouts;
            }
            if (layouts.isEmpty()) {
                System.err.println("no configured hardware keyboard layouts found");
                System.exit(3);
                return;
            }

            final String persistedCurrent = args.length >= 2
                    ? args[1] : null;
            String current = null;
            // The persisted descriptor represents the pre-shortcut state.
            if ("next".equals(args[0])
                    && findCurrentIndex(layouts, persistedCurrent) >= 0) {
                current = persistedCurrent;
            }
            if (current == null) {
                current = getSelectedLayoutDescriptor(
                        inputManager, inputManagerInterface,
                        keyboards.get(0), imeState.inputMethod,
                        imeState.currentSubtype);
            }
            if (current == null) {
                current = persistedCurrent;
            }
            final int currentIndex = findCurrentIndex(layouts, current);
            final int localeIndex = findLocaleIndex(layouts, imeState.currentSubtype);
            final int baseIndex = currentIndex >= 0 ? currentIndex : localeIndex;
            final int selectedIndex = "next".equals(args[0])
                    ? (baseIndex + 1) % layouts.size()
                    : Math.max(0, baseIndex);
            final LayoutInfo selected = layouts.get(selectedIndex);
            if ("next".equals(args[0])) {
                setKeyboardLayout(inputManager, inputManagerInterface,
                        keyboards, imeState, selected.descriptor);
            }

            System.out.println("descriptor=" + selected.descriptor);
            System.out.println("code=" + compactCode(layouts, selectedIndex));
            System.out.println("name64=" + Base64.encodeToString(
                    selected.label.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            System.out.println("layouts64=" + encodeLayoutDescriptors(layouts));
            System.out.println("devices=" + keyboards.size());
            System.out.println("layouts=" + layouts.size());
            System.out.println("ime=" + imeState.imeId);
            System.out.println("subtype=" + imeState.currentSubtype.hashCode());
            System.out.println("subtypes=" + imeState.layoutMappings.size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static List<LayoutInfo> resolveConfiguredLayouts(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final Method getKeyboardLayout,
            final Class<?> keyboardLayoutClass,
            final InputDevice keyboard,
            final ImeState imeState) throws ReflectiveOperationException {
        final Set<String> descriptors = new LinkedHashSet<>();
        for (final ImeSubtypeState mapping : imeState.layoutMappings) {
            final String descriptor = getSelectedLayoutDescriptor(
                    inputManager, inputManagerInterface,
                    keyboard, mapping.inputMethod, mapping.subtype);
            if (descriptor != null && !descriptor.isEmpty()) {
                descriptors.add(descriptor);
            }
        }
        return resolveLayouts(
                descriptors, inputManager, getKeyboardLayout,
                keyboardLayoutClass);
    }

    private static List<LayoutInfo> resolveLayouts(
            final Iterable<String> descriptors,
            final Object inputManager,
            final Method getKeyboardLayout,
            final Class<?> keyboardLayoutClass)
            throws ReflectiveOperationException {
        final Method getDescriptor = keyboardLayoutClass.getMethod("getDescriptor");
        final Method getLabel = keyboardLayoutClass.getMethod("getLabel");
        final Method getLocales = keyboardLayoutClass.getMethod("getLocales");
        final List<LayoutInfo> layouts = new ArrayList<>();
        for (final String descriptor : descriptors) {
            final Object keyboardLayout = getKeyboardLayout.invoke(inputManager, descriptor);
            if (keyboardLayout == null) {
                continue;
            }
            final String resolvedDescriptor = (String) getDescriptor.invoke(keyboardLayout);
            final String label = (String) getLabel.invoke(keyboardLayout);
            final LocaleList locales = (LocaleList) getLocales.invoke(keyboardLayout);
            layouts.add(new LayoutInfo(resolvedDescriptor, label, firstLocale(locales)));
        }
        return layouts;
    }

    private static List<LayoutInfo> resolvePersistedLayouts(
            final String encodedDescriptors,
            final Object inputManager,
            final Method getKeyboardLayout,
            final Class<?> keyboardLayoutClass) throws ReflectiveOperationException {
        if (encodedDescriptors == null
                || encodedDescriptors.isEmpty()
                || "null".equals(encodedDescriptors)) {
            return new ArrayList<>();
        }
        final String decoded;
        try {
            decoded = new String(
                    Base64.decode(encodedDescriptors, Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return new ArrayList<>();
        }
        final Set<String> descriptors = new LinkedHashSet<>();
        for (final String descriptor : decoded.split("\\n")) {
            final String trimmed = descriptor.trim();
            if (!trimmed.isEmpty()) {
                descriptors.add(trimmed);
            }
        }
        return resolveLayouts(
                descriptors, inputManager, getKeyboardLayout,
                keyboardLayoutClass);
    }

    private static String encodeLayoutDescriptors(
            final List<LayoutInfo> layouts) {
        final StringBuilder descriptors = new StringBuilder();
        for (final LayoutInfo layout : layouts) {
            if (descriptors.length() > 0) {
                descriptors.append('\n');
            }
            descriptors.append(layout.descriptor);
        }
        return Base64.encodeToString(
                descriptors.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
    }

    private static String getSelectedLayoutDescriptor(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final InputDevice keyboard,
            final InputMethodInfo inputMethod,
            final InputMethodSubtype subtype) throws ReflectiveOperationException {
        final Class<?> identifierClass =
                Class.forName("android.hardware.input.InputDeviceIdentifier");
        final Object identifier = InputDevice.class.getMethod("getIdentifier")
                .invoke(keyboard);
        final Object selection = inputManagerInterface.getMethod(
                "getKeyboardLayoutForInputDevice",
                identifierClass, int.class, InputMethodInfo.class,
                InputMethodSubtype.class)
                .invoke(inputManager, identifier, 0,
                        inputMethod, subtype);
        return selection == null ? null
                : (String) selection.getClass()
                        .getMethod("getLayoutDescriptor").invoke(selection);
    }

    private static List<InputDevice> getExternalAlphabeticKeyboards() {
        final List<InputDevice> keyboards = new ArrayList<>();
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (isExternalAlphabeticKeyboard(device)) {
                keyboards.add(device);
            }
        }
        return keyboards;
    }

    private static boolean isExternalAlphabeticKeyboard(final InputDevice device) {
        if (device == null || device.isVirtual() || !device.isExternal()) {
            return false;
        }
        final boolean hasKeyboardSource =
                (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return hasKeyboardSource
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static Object getInputManagerService() throws ReflectiveOperationException {
        return getService("input", "android.hardware.input.IInputManager");
    }

    private static Object getService(
            final String serviceName,
            final String interfaceName) throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final Object binder = serviceManager.getMethod("getService", String.class)
                .invoke(null, serviceName);
        final Class<?> stub = Class.forName(interfaceName + "$Stub");
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder);
    }

    @SuppressWarnings("unchecked")
    private static ImeState getImeState() throws ReflectiveOperationException {
        final String interfaceName = "com.android.internal.view.IInputMethodManager";
        final Object inputMethodManager = getService(INPUT_METHOD_SERVICE, interfaceName);
        final Class<?> inputMethodManagerInterface = Class.forName(interfaceName);
        final int userId = 0;
        final InputMethodInfo inputMethod = (InputMethodInfo) inputMethodManagerInterface.getMethod(
                "getCurrentInputMethodInfoAsUser", int.class)
                .invoke(inputMethodManager, userId);
        final InputMethodSubtype currentSubtype =
                (InputMethodSubtype) inputMethodManagerInterface.getMethod(
                        "getCurrentInputMethodSubtype", int.class)
                        .invoke(inputMethodManager, userId);
        if (inputMethod == null || currentSubtype == null) {
            throw new IllegalStateException("current input method or subtype is unavailable");
        }

        final List<InputMethodInfo> enabledInputMethods =
                (List<InputMethodInfo>) inputMethodManagerInterface.getMethod(
                        "getEnabledInputMethodListLegacy", int.class)
                        .invoke(inputMethodManager, userId);
        final List<ImeSubtypeState> layoutMappings = new ArrayList<>();
        final Set<String> seenMappings = new LinkedHashSet<>();
        for (final InputMethodInfo enabledInputMethod : enabledInputMethods) {
            final List<InputMethodSubtype> enabledSubtypes =
                    (List<InputMethodSubtype>) inputMethodManagerInterface.getMethod(
                            "getEnabledInputMethodSubtypeList",
                            String.class, boolean.class, int.class)
                            .invoke(inputMethodManager,
                                    enabledInputMethod.getId(), true, userId);
            for (final InputMethodSubtype subtype : enabledSubtypes) {
                final String mappingKey = enabledInputMethod.getId()
                        + ':' + (subtype == null ? 0 : subtype.hashCode());
                if (subtype == null
                        || !KEYBOARD_SUBTYPE_MODE.equals(subtype.getMode())
                        || !seenMappings.add(mappingKey)) {
                    continue;
                }
                layoutMappings.add(
                        new ImeSubtypeState(enabledInputMethod, subtype));
            }
        }
        final String currentMappingKey =
                inputMethod.getId() + ':' + currentSubtype.hashCode();
        if (seenMappings.add(currentMappingKey)) {
            layoutMappings.add(
                    new ImeSubtypeState(inputMethod, currentSubtype));
        }
        return new ImeState(inputMethod, currentSubtype, layoutMappings);
    }

    private static void setKeyboardLayout(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final List<InputDevice> keyboards,
            final ImeState imeState,
            final String descriptor) throws ReflectiveOperationException {
        final Class<?> identifierClass =
                Class.forName("android.hardware.input.InputDeviceIdentifier");
        final Method getIdentifier = InputDevice.class.getMethod("getIdentifier");
        final Method setLayout = inputManagerInterface.getMethod(
                "setKeyboardLayoutForInputDevice",
                identifierClass, int.class, InputMethodInfo.class,
                InputMethodSubtype.class, String.class);
        final Method getLayout = inputManagerInterface.getMethod(
                "getKeyboardLayoutForInputDevice",
                identifierClass, int.class, InputMethodInfo.class,
                InputMethodSubtype.class);
        for (final InputDevice keyboard : keyboards) {
            final Object identifier = getIdentifier.invoke(keyboard);
            setLayout.invoke(inputManager, identifier, 0,
                    imeState.inputMethod, imeState.currentSubtype, descriptor);
            final Object selection = getLayout.invoke(inputManager, identifier, 0,
                    imeState.inputMethod, imeState.currentSubtype);
            final String applied = selection == null ? null
                    : (String) selection.getClass()
                            .getMethod("getLayoutDescriptor").invoke(selection);
            if (!descriptor.equals(applied)) {
                throw new IllegalStateException(
                        "keyboard layout did not change to " + descriptor);
            }
        }
    }

    private static int findCurrentIndex(final List<LayoutInfo> layouts, final String current) {
        if (current == null || current.isEmpty() || "null".equals(current)) {
            return -1;
        }
        for (int index = 0; index < layouts.size(); index++) {
            final String descriptor = layouts.get(index).descriptor;
            // Pre-descriptor builds persisted these two symbolic values globally.
            if (descriptor.equals(current)
                    || ("english".equals(current)
                            && descriptor.endsWith(LEGACY_ENGLISH_SUFFIX))
                    || ("russian".equals(current)
                            && descriptor.endsWith(LEGACY_RUSSIAN_SUFFIX))) {
                return index;
            }
        }
        return -1;
    }

    private static int findLocaleIndex(
            final List<LayoutInfo> layouts,
            final InputMethodSubtype subtype) {
        final Locale subtypeLocale = localeOf(subtype);
        if (subtypeLocale == null || subtypeLocale.getLanguage().isEmpty()) {
            return -1;
        }
        for (int index = 0; index < layouts.size(); index++) {
            final Locale layoutLocale = layouts.get(index).locale;
            if (layoutLocale != null
                    && subtypeLocale.getLanguage().equals(layoutLocale.getLanguage())
                    && (subtypeLocale.getCountry().isEmpty()
                            || layoutLocale.getCountry().isEmpty()
                            || subtypeLocale.getCountry().equals(layoutLocale.getCountry()))) {
                return index;
            }
        }
        return -1;
    }

    private static Locale localeOf(final InputMethodSubtype subtype) {
        final String languageTag = subtype.getLanguageTag();
        if (languageTag != null && !languageTag.isEmpty()) {
            return Locale.forLanguageTag(languageTag);
        }
        final String locale = subtype.getLocale();
        return locale == null || locale.isEmpty()
                ? null : Locale.forLanguageTag(locale.replace('_', '-'));
    }

    private static Locale firstLocale(final LocaleList locales) {
        return locales == null || locales.isEmpty() ? null : locales.get(0);
    }

    private static String compactCode(final List<LayoutInfo> layouts, final int selectedIndex) {
        final LayoutInfo selected = layouts.get(selectedIndex);
        final String language = languageCode(selected.locale);
        int matchingLanguages = 0;
        for (final LayoutInfo layout : layouts) {
            if (language.equals(languageCode(layout.locale))) {
                matchingLanguages++;
            }
        }
        if (matchingLanguages <= 1) {
            return language;
        }

        final String country = selected.locale == null
                ? "" : selected.locale.getCountry().toUpperCase(Locale.ROOT);
        final String regionalCode = country.isEmpty() ? language : language + "-" + country;
        int matchingRegionalCodes = 0;
        for (final LayoutInfo layout : layouts) {
            final String layoutLanguage = languageCode(layout.locale);
            final String layoutCountry = layout.locale == null
                    ? "" : layout.locale.getCountry().toUpperCase(Locale.ROOT);
            final String layoutRegionalCode = layoutCountry.isEmpty()
                    ? layoutLanguage : layoutLanguage + "-" + layoutCountry;
            if (regionalCode.equals(layoutRegionalCode)) {
                matchingRegionalCodes++;
            }
        }
        if (matchingRegionalCodes <= 1) {
            return regionalCode;
        }
        int variantIndex = 0;
        for (int index = 0; index <= selectedIndex; index++) {
            final LayoutInfo layout = layouts.get(index);
            final String layoutLanguage = languageCode(layout.locale);
            final String layoutCountry = layout.locale == null
                    ? "" : layout.locale.getCountry().toUpperCase(Locale.ROOT);
            final String layoutRegionalCode = layoutCountry.isEmpty()
                    ? layoutLanguage : layoutLanguage + "-" + layoutCountry;
            if (regionalCode.equals(layoutRegionalCode)) {
                variantIndex++;
            }
        }
        return regionalCode + "-" + variantIndex;
    }

    private static String languageCode(final Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return "??";
        }
        return locale.getLanguage().toUpperCase(Locale.ROOT);
    }

    private static final class LayoutInfo {
        final String descriptor;
        final String label;
        final Locale locale;

        LayoutInfo(final String descriptor, final String label, final Locale locale) {
            this.descriptor = descriptor;
            this.label = label == null || label.isEmpty() ? descriptor : label;
            this.locale = locale;
        }
    }

    private static final class ImeState {
        final InputMethodInfo inputMethod;
        final String imeId;
        final InputMethodSubtype currentSubtype;
        final List<ImeSubtypeState> layoutMappings;

        ImeState(
                final InputMethodInfo inputMethod,
                final InputMethodSubtype currentSubtype,
                final List<ImeSubtypeState> layoutMappings) {
            this.inputMethod = inputMethod;
            this.imeId = inputMethod.getId();
            this.currentSubtype = currentSubtype;
            this.layoutMappings = layoutMappings;
        }
    }

    private static final class ImeSubtypeState {
        final InputMethodInfo inputMethod;
        final InputMethodSubtype subtype;

        ImeSubtypeState(
                final InputMethodInfo inputMethod,
                final InputMethodSubtype subtype) {
            this.inputMethod = inputMethod;
            this.subtype = subtype;
        }
    }
}
