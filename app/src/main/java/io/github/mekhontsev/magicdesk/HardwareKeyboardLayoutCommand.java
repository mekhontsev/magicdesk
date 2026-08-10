package io.github.mekhontsev.magicdesk;

import android.icu.util.ULocale;
import android.os.IBinder;
import android.os.LocaleList;
import android.util.Base64;
import android.view.Display;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HardwareKeyboardLayoutCommand {
    private static final String INPUT_METHOD_SERVICE = "input_method";
    private static final String INPUT_METHOD_SUBTYPE_SAFE_LIST =
            "com.android.internal.inputmethod.InputMethodSubtypeSafeList";
    private static final String KEYBOARD_SUBTYPE_MODE = "keyboard";
    private static final String MAGICDESK_VIRTUAL_KEYBOARD_NAME =
            "MagicDesk Keyboard";
    private static final String MAGICDESK_VIRTUAL_KEYBOARD_PREFIX =
            MAGICDESK_VIRTUAL_KEYBOARD_NAME + " ";
    static final String STATUS_NO_EXTERNAL_KEYBOARD =
            "no_external_keyboard";

    private HardwareKeyboardLayoutCommand() {
    }

    public static void main(final String[] args) {
        if ((args.length < 1 || args.length > 2)
                || !("next".equals(args[0])
                        || "sync".equals(args[0])
                        || "ime".equals(args[0])
                        || "catalog".equals(args[0]))) {
            System.err.println(
                    "usage: HardwareKeyboardLayoutCommand"
                            + " <next|sync|ime|catalog>"
                            + " [current-descriptor]");
            System.exit(64);
            return;
        }

        try {
            System.out.print(execute(
                    args[0], args.length >= 2 ? args[1] : null).format());
        } catch (ReflectiveOperationException | RuntimeException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static Result execute(
            final String mode,
            final String persistedCurrent)
            throws ReflectiveOperationException {
        if (!"next".equals(mode)
                && !"sync".equals(mode)
                && !"ime".equals(mode)
                && !"catalog".equals(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        final List<InputDevice> physicalKeyboards =
                getExternalAlphabeticKeyboards();
        if (physicalKeyboards.isEmpty()) {
            return Result.noExternalKeyboard();
        }
        final Object inputManager = getInputManagerService();
        final Class<?> inputManagerInterface =
                Class.forName("android.hardware.input.IInputManager");
        final Class<?> keyboardLayoutClass =
                Class.forName("android.hardware.input.KeyboardLayout");
        final Method getKeyboardLayout = inputManagerInterface.getMethod(
                "getKeyboardLayout", String.class);
        if ("next".equals(mode)) {
            switchInputMethodSubtype();
        }
        final ImeState imeState = getImeState();
        final List<LayoutInfo> layouts = resolveConfiguredLayouts(
                inputManager, inputManagerInterface, getKeyboardLayout,
                keyboardLayoutClass, physicalKeyboards.get(0), imeState);
        if (layouts.isEmpty()) {
            throw new IllegalStateException(
                    "no configured hardware keyboard layouts found");
        }
        // Virtual keyboard indexes are a protocol shared with the native
        // bridge, so changing the current IME subtype must not reorder them.
        layouts.sort(Comparator.comparing(layout -> layout.descriptor));

        final int subtypeIndex =
                findSubtypeIndex(layouts, imeState.currentSubtype);
        final int persistedIndex =
                KeyboardLayoutPolicy.findCurrentIndex(
                        layouts, persistedCurrent);
        final int baseIndex = persistedIndex >= 0
                ? persistedIndex : Math.max(0, subtypeIndex);
        final int selectedIndex;
        if (("next".equals(mode) || "ime".equals(mode))
                && subtypeIndex >= 0) {
            selectedIndex = subtypeIndex;
        } else {
            selectedIndex = baseIndex;
        }
        final LayoutInfo selected = layouts.get(selectedIndex);
        final List<IndexedKeyboard> virtualKeyboards =
                getIndexedVirtualKeyboards();
        final int deviceCount;
        if ("catalog".equals(mode)) {
            deviceCount = virtualKeyboards.size();
        } else if (!virtualKeyboards.isEmpty()) {
            if (virtualKeyboards.size() != layouts.size()) {
                throw new IllegalStateException(
                        "virtual keyboard count "
                                + virtualKeyboards.size()
                                + " does not match layout count "
                                + layouts.size());
            }
            if ("sync".equals(mode)) {
                for (int index = 0; index < layouts.size(); index++) {
                    setKeyboardLayout(
                            inputManager,
                            inputManagerInterface,
                            virtualKeyboards.get(index).device,
                            layouts.get(index).inputMethod,
                            layouts.get(index),
                            false);
                }
            }
            deviceCount = virtualKeyboards.size();
        } else {
            for (final InputDevice keyboard : physicalKeyboards) {
                setKeyboardLayout(
                        inputManager,
                        inputManagerInterface,
                        keyboard,
                        selected.inputMethod,
                        selected,
                        true);
            }
            deviceCount = physicalKeyboards.size();
        }

        return new Result(
                selected.descriptor,
                KeyboardLayoutPolicy.compactCode(layouts, selectedIndex),
                selected.label,
                selectedIndex,
                deviceCount,
                physicalKeyboards.size(),
                layouts.size(),
                imeState.imeId);
    }

    private static void switchInputMethodSubtype()
            throws ReflectiveOperationException {
        final Class<?> serviceManager =
                Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class)
                .invoke(null, INPUT_METHOD_SERVICE);
        if (binder == null) {
            throw new IllegalStateException(
                    "input method service is unavailable");
        }
        final Class<?> inputMethodStub = Class.forName(
                "com.android.internal.view.IInputMethodManager$Stub");
        final Object inputMethodManager = inputMethodStub
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        Class.forName("com.android.internal.view.IInputMethodManager")
                .getMethod(
                        "onImeSwitchButtonClickFromSystem", int.class)
                .invoke(inputMethodManager, Display.DEFAULT_DISPLAY);
    }

    private static List<LayoutInfo> resolveConfiguredLayouts(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final Method getKeyboardLayout,
            final Class<?> keyboardLayoutClass,
            final InputDevice keyboard,
            final ImeState imeState) throws ReflectiveOperationException {
        final Method getDescriptor =
                keyboardLayoutClass.getMethod("getDescriptor");
        final Method getLabel =
                keyboardLayoutClass.getMethod("getLabel");
        final Method getLocales =
                keyboardLayoutClass.getMethod("getLocales");
        final Method getLayoutType =
                keyboardLayoutClass.getMethod("getLayoutType");
        final Class<?> identifierClass =
                Class.forName("android.hardware.input.InputDeviceIdentifier");
        final Object identifier = InputDevice.class
                .getMethod("getIdentifier").invoke(keyboard);
        final Method getLayoutList = inputManagerInterface.getMethod(
                "getKeyboardLayoutListForInputDevice",
                identifierClass,
                int.class,
                InputMethodInfo.class,
                InputMethodSubtype.class);

        final List<LayoutInfo> layouts = new ArrayList<>();
        final Set<String> seenDescriptors = new LinkedHashSet<>();
        for (final ImeSubtypeState mapping : imeState.layoutMappings) {
            final Object candidates = getLayoutList.invoke(
                    inputManager,
                    identifier,
                    0,
                    mapping.inputMethod,
                    mapping.subtype);
            final List<ResolvedLayout> resolvedLayouts =
                    new ArrayList<>();
            for (int index = 0;
                    candidates != null
                            && index < Array.getLength(candidates);
                    index++) {
                final Object candidate = Array.get(candidates, index);
                resolvedLayouts.add(new ResolvedLayout(
                        (String) getDescriptor.invoke(candidate),
                        (String) getLabel.invoke(candidate),
                        (LocaleList) getLocales.invoke(candidate),
                        (String) getLayoutType.invoke(candidate)));
            }
            ResolvedLayout resolved = findBestLayout(
                    resolvedLayouts,
                    localeOf(mapping.subtype),
                    mapping.subtype
                            .getPhysicalKeyboardHintLayoutType());
            if (resolved == null) {
                final String configuredDescriptor =
                        getSelectedLayoutDescriptor(
                                inputManager,
                                inputManagerInterface,
                                keyboard,
                                mapping.inputMethod,
                                mapping.subtype);
                resolved = findResolvedLayout(
                        resolvedLayouts, configuredDescriptor);
            }
            if (resolved == null) {
                continue;
            }
            if (!seenDescriptors.add(resolved.descriptor)) {
                continue;
            }
            layouts.add(new LayoutInfo(
                    resolved.descriptor,
                    resolved.label,
                    preferredLocale(
                            mapping.subtype, resolved.locales),
                    mapping.inputMethod,
                    mapping.subtype));
        }
        return layouts;
    }

    private static ResolvedLayout findResolvedLayout(
            final List<ResolvedLayout> layouts,
            final String descriptor) {
        if (descriptor == null) {
            return null;
        }
        for (final ResolvedLayout layout : layouts) {
            if (descriptor.equals(layout.descriptor)) {
                return layout;
            }
        }
        return null;
    }

    private static ResolvedLayout findBestLayout(
            final List<ResolvedLayout> layouts,
            final Locale subtypeLocale,
            final String subtypeLayoutType) {
        if (subtypeLocale == null
                || subtypeLocale.getLanguage().isEmpty()) {
            return null;
        }
        ResolvedLayout best = null;
        int bestScore = -1;
        for (final ResolvedLayout layout : layouts) {
            for (int index = 0; index < layout.locales.size(); index++) {
                final Locale layoutLocale = layout.locales.get(index);
                if (!subtypeLocale.getLanguage().equals(
                        layoutLocale.getLanguage())) {
                    continue;
                }
                int score = 1;
                if (!subtypeLocale.getCountry().isEmpty()
                        && subtypeLocale.getCountry().equals(
                                layoutLocale.getCountry())) {
                    score += 2;
                }
                if (subtypeLayoutType != null
                        && !subtypeLayoutType.isEmpty()
                        && subtypeLayoutType.equals(
                                layout.layoutType)) {
                    score++;
                }
                if (score > bestScore) {
                    best = layout;
                    bestScore = score;
                }
            }
        }
        return best;
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
        if (device == null
                || device.isVirtual()
                || !device.isExternal()
                || isMagicDeskVirtualKeyboard(device)) {
            return false;
        }
        final boolean hasKeyboardSource =
                (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return hasKeyboardSource
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static List<IndexedKeyboard> getIndexedVirtualKeyboards() {
        final List<IndexedKeyboard> keyboards = new ArrayList<>();
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            final int index = virtualKeyboardIndex(device);
            if (index >= 0) {
                keyboards.add(new IndexedKeyboard(index, device));
            }
        }
        keyboards.sort(Comparator.comparingInt(value -> value.index));
        for (int index = 0; index < keyboards.size(); index++) {
            if (keyboards.get(index).index != index) {
                throw new IllegalStateException(
                        "missing MagicDesk virtual keyboard " + index);
            }
        }
        return keyboards;
    }

    private static boolean isMagicDeskVirtualKeyboard(
            final InputDevice device) {
        if (device == null) {
            return false;
        }
        final String name = device.getName();
        return name.startsWith(MAGICDESK_VIRTUAL_KEYBOARD_PREFIX)
                && (device.getSources() & InputDevice.SOURCE_KEYBOARD)
                        == InputDevice.SOURCE_KEYBOARD;
    }

    private static int virtualKeyboardIndex(final InputDevice device) {
        if (!isMagicDeskVirtualKeyboard(device)) {
            return -1;
        }
        final String name = device.getName();
        if (!name.startsWith(MAGICDESK_VIRTUAL_KEYBOARD_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(
                    name.substring(
                            MAGICDESK_VIRTUAL_KEYBOARD_PREFIX.length()));
        } catch (NumberFormatException error) {
            return -1;
        }
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

        final List<ImeSubtypeState> layoutMappings = new ArrayList<>();
        final Set<String> seenMappings = new LinkedHashSet<>();
        addSubtypeMapping(
                layoutMappings, seenMappings, inputMethod, currentSubtype);
        final List<InputMethodInfo> enabledInputMethods =
                (List<InputMethodInfo>) inputMethodManagerInterface.getMethod(
                        "getEnabledInputMethodListLegacy", int.class)
                        .invoke(inputMethodManager, userId);
        for (final InputMethodInfo enabledInputMethod : enabledInputMethods) {
            final Object enabledSubtypeResult = inputMethodManagerInterface.getMethod(
                    "getEnabledInputMethodSubtypeList",
                    String.class, boolean.class, int.class)
                    .invoke(inputMethodManager,
                            enabledInputMethod.getId(), true, userId);
            final List<InputMethodSubtype> enabledSubtypes =
                    extractEnabledInputMethodSubtypes(enabledSubtypeResult);
            for (final InputMethodSubtype subtype : enabledSubtypes) {
                addSubtypeMapping(
                        layoutMappings, seenMappings,
                        enabledInputMethod, subtype);
            }
        }
        return new ImeState(inputMethod, currentSubtype, layoutMappings);
    }

    @SuppressWarnings("unchecked")
    static List<InputMethodSubtype> extractEnabledInputMethodSubtypes(
            final Object result) throws ReflectiveOperationException {
        if (result == null) {
            return new ArrayList<>();
        }
        if (result instanceof List<?>) {
            return (List<InputMethodSubtype>) result;
        }
        final Class<?> resultClass = result.getClass();
        if (!INPUT_METHOD_SUBTYPE_SAFE_LIST.equals(resultClass.getName())) {
            throw new IllegalStateException(
                    "unsupported enabled subtype result: "
                            + resultClass.getName());
        }
        final Object extracted = resultClass.getMethod(
                "extractFrom", resultClass).invoke(null, result);
        if (!(extracted instanceof List<?>)) {
            throw new IllegalStateException(
                    "extracted subtype result is not a List");
        }
        return (List<InputMethodSubtype>) extracted;
    }

    private static void addSubtypeMapping(
            final List<ImeSubtypeState> mappings,
            final Set<String> seenMappings,
            final InputMethodInfo inputMethod,
            final InputMethodSubtype subtype) {
        if (inputMethod == null || subtype == null
                || !KEYBOARD_SUBTYPE_MODE.equals(subtype.getMode())) {
            return;
        }
        final String key = inputMethod.getId() + ':' + subtype.hashCode();
        if (seenMappings.add(key)) {
            mappings.add(new ImeSubtypeState(inputMethod, subtype));
        }
    }

    private static void setKeyboardLayout(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final InputDevice keyboard,
            final InputMethodInfo inputMethod,
            final LayoutInfo selected,
            final boolean verify) throws ReflectiveOperationException {
        final Class<?> identifierClass =
                Class.forName("android.hardware.input.InputDeviceIdentifier");
        final Method getIdentifier = InputDevice.class.getMethod("getIdentifier");
        final Method setOverride = inputManagerInterface.getMethod(
                "setKeyboardLayoutOverrideForInputDevice",
                identifierClass, String.class);
        final Method setLayout = inputManagerInterface.getMethod(
                "setKeyboardLayoutForInputDevice",
                identifierClass, int.class, InputMethodInfo.class,
                InputMethodSubtype.class, String.class);
        final Method getLayout = inputManagerInterface.getMethod(
                "getKeyboardLayoutForInputDevice",
                identifierClass, int.class, InputMethodInfo.class,
                InputMethodSubtype.class);
        final Object identifier = getIdentifier.invoke(keyboard);
        Object selection = getLayout.invoke(inputManager, identifier, 0,
                inputMethod, selected.subtype);
        String applied = selection == null ? null
                : (String) selection.getClass()
                        .getMethod("getLayoutDescriptor").invoke(selection);
        if (verify && selected.descriptor.equals(applied)) {
            return;
        }
        setOverride.invoke(inputManager, identifier, selected.descriptor);
        setLayout.invoke(inputManager, identifier, 0,
                inputMethod, selected.subtype, selected.descriptor);
        if (!verify) {
            return;
        }
        selection = getLayout.invoke(inputManager, identifier, 0,
                inputMethod, selected.subtype);
        applied = selection == null ? null
                : (String) selection.getClass()
                        .getMethod("getLayoutDescriptor").invoke(selection);
        if (!selected.descriptor.equals(applied)) {
            throw new IllegalStateException(
                    "keyboard layout did not change to "
                            + selected.descriptor);
        }
    }

    private static int findSubtypeIndex(
            final List<LayoutInfo> layouts,
            final InputMethodSubtype currentSubtype) {
        for (int index = 0; index < layouts.size(); index++) {
            if (layouts.get(index).subtype.equals(currentSubtype)) {
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
        if (locale != null && !locale.isEmpty()) {
            return Locale.forLanguageTag(locale.replace('_', '-'));
        }
        final ULocale physicalLanguage =
                subtype.getPhysicalKeyboardHintLanguageTag();
        return physicalLanguage == null
                ? null : physicalLanguage.toLocale();
    }

    private static Locale firstLocale(final LocaleList locales) {
        return locales == null || locales.isEmpty() ? null : locales.get(0);
    }

    private static Locale preferredLocale(
            final InputMethodSubtype subtype,
            final LocaleList layoutLocales) {
        final Locale subtypeLocale = localeOf(subtype);
        return subtypeLocale == null
                ? firstLocale(layoutLocales) : subtypeLocale;
    }

    static final class Result {
        final String descriptor;
        final String code;
        final String name;
        final int index;
        final int devices;
        final int physicalDevices;
        final int layouts;
        final String imeId;

        static Result noExternalKeyboard() {
            return new Result(null, null, null, -1, 0, 0, 0, null);
        }

        Result(
                final String descriptor,
                final String code,
                final String name,
                final int index,
                final int devices,
                final int physicalDevices,
                final int layouts,
                final String imeId) {
            this.descriptor = descriptor;
            this.code = code;
            this.name = name;
            this.index = index;
            this.devices = devices;
            this.physicalDevices = physicalDevices;
            this.layouts = layouts;
            this.imeId = imeId;
        }

        boolean isAvailable() {
            return descriptor != null;
        }

        String format() {
            if (!isAvailable()) {
                return "status=" + STATUS_NO_EXTERNAL_KEYBOARD + '\n'
                        + "physicalDevices=0\n"
                        + "layouts=0\n";
            }
            return "descriptor=" + descriptor + '\n'
                    + "code=" + code + '\n'
                    + "index=" + index + '\n'
                    + "name64=" + Base64.encodeToString(
                            name.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP) + '\n'
                    + "devices=" + devices + '\n'
                    + "physicalDevices=" + physicalDevices + '\n'
                    + "layouts=" + layouts + '\n'
                    + "ime=" + imeId + '\n';
        }
    }

    private static final class IndexedKeyboard {
        final int index;
        final InputDevice device;

        IndexedKeyboard(final int index, final InputDevice device) {
            this.index = index;
            this.device = device;
        }
    }

    private static final class LayoutInfo
            implements KeyboardLayoutPolicy.Layout {
        final String descriptor;
        final String label;
        final Locale locale;
        final InputMethodInfo inputMethod;
        final InputMethodSubtype subtype;

        LayoutInfo(
                final String descriptor,
                final String label,
                final Locale locale,
                final InputMethodInfo inputMethod,
                final InputMethodSubtype subtype) {
            this.descriptor = descriptor;
            this.label = label == null || label.isEmpty() ? descriptor : label;
            this.locale = locale;
            this.inputMethod = inputMethod;
            this.subtype = subtype;
        }

        @Override
        public String descriptor() {
            return descriptor;
        }

        @Override
        public Locale locale() {
            return locale;
        }
    }

    private static final class ResolvedLayout {
        final String descriptor;
        final String label;
        final LocaleList locales;
        final String layoutType;

        ResolvedLayout(
                final String descriptor,
                final String label,
                final LocaleList locales,
                final String layoutType) {
            this.descriptor = descriptor;
            this.label = label;
            this.locales = locales == null
                    ? LocaleList.getEmptyLocaleList() : locales;
            this.layoutType = layoutType;
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
