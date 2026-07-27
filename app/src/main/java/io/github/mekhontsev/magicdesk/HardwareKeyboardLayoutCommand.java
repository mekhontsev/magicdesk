package io.github.mekhontsev.magicdesk;

import android.os.LocaleList;
import android.util.Base64;
import android.util.Xml;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import org.xmlpull.v1.XmlPullParser;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HardwareKeyboardLayoutCommand {
    private static final String INPUT_MANAGER_STATE = "/data/system/input-manager-state.xml";
    private static final String INPUT_METHOD_SERVICE = "input_method";
    private static final String KEYBOARD_SUBTYPE_MODE = "keyboard";
    private static final String LEGACY_ENGLISH_SUFFIX = "/keyboard_layout_english_us";
    private static final String LEGACY_RUSSIAN_SUFFIX = "/keyboard_layout_russian";

    private HardwareKeyboardLayoutCommand() {
    }

    public static void main(final String[] args) {
        if ((args.length < 1 || args.length > 2)
                || !("next".equals(args[0]) || "sync".equals(args[0]))) {
            System.err.println(
                    "usage: HardwareKeyboardLayoutCommand <next|sync> [current-descriptor]");
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
            final List<LayoutInfo> layouts = resolveConfiguredLayouts(
                    inputManager, getKeyboardLayout, keyboardLayoutClass, keyboards.get(0));
            if (layouts.isEmpty()) {
                System.err.println("no configured hardware keyboard layouts found");
                System.exit(3);
                return;
            }

            final ImeState imeState = getImeState();
            String current = readCurrentLayout(keyboards.get(0), imeState);
            if (current == null && args.length >= 2) {
                current = args[1];
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
            System.out.println("devices=" + keyboards.size());
            System.out.println("layouts=" + layouts.size());
            System.out.println("ime=" + imeState.imeId);
            System.out.println("subtype=" + imeState.currentSubtype.hashCode());
            System.out.println("subtypes=" + imeState.keyboardSubtypes.size());
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static List<LayoutInfo> resolveConfiguredLayouts(
            final Object inputManager,
            final Method getKeyboardLayout,
            final Class<?> keyboardLayoutClass,
            final InputDevice keyboard) throws ReflectiveOperationException, IOException {
        List<String> descriptors = readConfiguredDescriptors(keyboard);
        if (descriptors.isEmpty()) {
            descriptors = readFirstConfiguredDescriptorSet();
        }

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

    private static List<String> readConfiguredDescriptors(final InputDevice keyboard)
            throws IOException {
        return readConfiguredDescriptors(getDeviceDescriptorAliases(keyboard), false);
    }

    private static String readCurrentLayout(
            final InputDevice keyboard,
            final ImeState imeState) throws IOException {
        final Set<String> aliases = getDeviceDescriptorAliases(keyboard);
        boolean insideAcceptedDevice = false;
        String globalOverride = null;
        final String subtypeKeySuffix = "subtypeHandle:" + imeState.imeId
                + ":subtype:" + imeState.currentSubtype.hashCode();
        try (FileInputStream input = new FileInputStream(INPUT_MANAGER_STATE)) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, StandardCharsets.UTF_8.name());
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG
                        && "input-device".equals(parser.getName())) {
                    insideAcceptedDevice = aliases.contains(
                            parser.getAttributeValue(null, "descriptor"));
                } else if (eventType == XmlPullParser.START_TAG
                        && insideAcceptedDevice
                        && "keyed-keyboard-layout".equals(parser.getName())) {
                    final String key = parser.getAttributeValue(null, "key");
                    final String layout = parser.getAttributeValue(null, "layout");
                    if (key != null && key.endsWith(subtypeKeySuffix)) {
                        return layout;
                    }
                    if ("GLOBAL_OVERRIDE_KEY".equals(key)) {
                        globalOverride = layout;
                    }
                } else if (eventType == XmlPullParser.END_TAG
                        && "input-device".equals(parser.getName())) {
                    insideAcceptedDevice = false;
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("cannot parse input manager state", e);
        }
        return globalOverride;
    }

    private static Set<String> getDeviceDescriptorAliases(final InputDevice keyboard) {
        final Set<String> aliases = new LinkedHashSet<>();
        aliases.add(keyboard.getDescriptor());
        if (keyboard.getVendorId() != 0 || keyboard.getProductId() != 0) {
            aliases.add("vendor:" + keyboard.getVendorId()
                    + ",product:" + keyboard.getProductId());
        }
        return aliases;
    }

    private static List<String> readFirstConfiguredDescriptorSet() throws IOException {
        return readConfiguredDescriptors(new LinkedHashSet<String>(), true);
    }

    private static List<String> readConfiguredDescriptors(
            final Set<String> acceptedDeviceDescriptors,
            final boolean acceptFirstConfiguredDevice) throws IOException {
        final LinkedHashSet<String> descriptors = new LinkedHashSet<>();
        boolean insideAcceptedDevice = false;
        boolean foundConfiguredDevice = false;
        try (FileInputStream input = new FileInputStream(INPUT_MANAGER_STATE)) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, StandardCharsets.UTF_8.name());
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG
                        && "input-device".equals(parser.getName())) {
                    final String descriptor = parser.getAttributeValue(null, "descriptor");
                    insideAcceptedDevice = acceptFirstConfiguredDevice
                            ? !foundConfiguredDevice
                            : acceptedDeviceDescriptors.contains(descriptor);
                } else if (eventType == XmlPullParser.START_TAG
                        && insideAcceptedDevice
                        && "selected-keyboard-layout".equals(parser.getName())) {
                    final String layout = parser.getAttributeValue(null, "layout");
                    if (layout != null && !layout.isEmpty()) {
                        descriptors.add(layout);
                        foundConfiguredDevice = true;
                    }
                } else if (eventType == XmlPullParser.END_TAG
                        && "input-device".equals(parser.getName())) {
                    if (acceptFirstConfiguredDevice && foundConfiguredDevice) {
                        break;
                    }
                    insideAcceptedDevice = false;
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("cannot parse input manager state", e);
        }
        return new ArrayList<>(descriptors);
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

        final List<InputMethodSubtype> enabled =
                (List<InputMethodSubtype>) inputMethodManagerInterface.getMethod(
                        "getEnabledInputMethodSubtypeList",
                        String.class, boolean.class, int.class)
                        .invoke(inputMethodManager, inputMethod.getId(), true, userId);
        final List<InputMethodSubtype> keyboardSubtypes = new ArrayList<>();
        final Set<Integer> seenHashes = new LinkedHashSet<>();
        for (final InputMethodSubtype subtype : enabled) {
            if (subtype == null
                    || !KEYBOARD_SUBTYPE_MODE.equals(subtype.getMode())
                    || !seenHashes.add(Integer.valueOf(subtype.hashCode()))) {
                continue;
            }
            keyboardSubtypes.add(subtype);
        }
        if (keyboardSubtypes.isEmpty()) {
            keyboardSubtypes.add(currentSubtype);
        }
        return new ImeState(inputMethod, currentSubtype, keyboardSubtypes);
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
        final List<InputMethodSubtype> keyboardSubtypes;

        ImeState(
                final InputMethodInfo inputMethod,
                final InputMethodSubtype currentSubtype,
                final List<InputMethodSubtype> keyboardSubtypes) {
            this.inputMethod = inputMethod;
            this.imeId = inputMethod.getId();
            this.currentSubtype = currentSubtype;
            this.keyboardSubtypes = keyboardSubtypes;
        }
    }
}
