package io.github.mekhontsev.magicdesk;

import android.icu.util.ULocale;
import android.os.LocaleList;
import android.util.Base64;
import android.view.Display;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import io.github.mekhontsev.magicdesk.FrameworkKeyboardLayoutApi.ResolvedLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HardwareKeyboardLayoutCommand {
    private static final String KEYBOARD_SUBTYPE_MODE = "keyboard";
    static final String STATUS_NO_EXTERNAL_KEYBOARD =
            "no_external_keyboard";

    private HardwareKeyboardLayoutCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 1
                || !("next".equals(args[0])
                        || "sync".equals(args[0]))) {
            System.err.println(
                    "usage: HardwareKeyboardLayoutCommand"
                            + " <next|sync>");
            System.exit(64);
            return;
        }

        try {
            System.out.print(execute(args[0]).format());
        } catch (ReflectiveOperationException | RuntimeException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static Result execute(final String mode)
            throws ReflectiveOperationException {
        return executeSelection(mode, null);
    }

    static Result executeSelection(final String mode, final String targetDescriptor)
            throws ReflectiveOperationException {
        if (!"next".equals(mode)
                && !"sync".equals(mode) && !"select".equals(mode)) {
            throw new IllegalArgumentException("unsupported mode: " + mode);
        }
        final boolean selecting = "select".equals(mode);
        if (selecting && (targetDescriptor == null || targetDescriptor.isEmpty())) {
            throw new IllegalArgumentException("layout descriptor is required");
        }
        final List<InputDevice> physicalKeyboards =
                getExternalAlphabeticKeyboards();
        if (physicalKeyboards.isEmpty()) {
            return Result.noExternalKeyboard();
        }
        final FrameworkKeyboardLayoutApi inputManager = new FrameworkKeyboardLayoutApi();
        final boolean advance = "next".equals(mode);
        ImeState imeState = getImeState();
        int remainingSwitches = advance || selecting
                ? Math.max(1, imeState.layoutMappings.size())
                : 0;
        List<LayoutInfo> layouts;
        int selectedIndex;
        String initialDescriptor = null;
        while (true) {
            layouts = resolveConfiguredLayouts(
                    inputManager, physicalKeyboards.get(0), imeState);
            if (layouts.isEmpty()) {
                throw new IllegalStateException(
                        "no configured hardware keyboard layouts found");
            }
            // Stable ordering is only for labels, never a fallback selection.
            layouts.sort(Comparator.comparing(layout -> layout.descriptor));
            if (selecting && layouts.stream().noneMatch(layout -> layout.descriptor.equals(targetDescriptor))) {
                throw new IllegalArgumentException("hardware keyboard layout is no longer configured");
            }
            selectedIndex = findSubtypeIndex(
                    layouts, imeState.currentSubtype);
            if (selectedIndex < 0) {
                throw new IllegalStateException(
                        "current input method subtype has no hardware keyboard layout");
            }
            final String descriptor = layouts.get(selectedIndex).descriptor;
            if (selecting ? targetDescriptor.equals(descriptor)
                    : remainingSwitches == 0 || (initialDescriptor != null && !initialDescriptor.equals(descriptor))) {
                break;
            }
            if (remainingSwitches == 0) {
                throw new IllegalStateException("Android did not select the requested keyboard layout");
            }
            // Compare with Android's state before this command, not the last
            // taskbar label. Different IMEs may expose the same layout.
            initialDescriptor = descriptor;
            remainingSwitches--;
            new FrameworkInputMethodCatalogApi().switchSubtype(Display.DEFAULT_DISPLAY);
            imeState = getImeState();
        }
        final LayoutInfo selected = layouts.get(selectedIndex);
        for (final InputDevice keyboard : physicalKeyboards) {
            inputManager.apply(keyboard, 0, selected.inputMethod, selected.subtype, selected.descriptor);
        }

        return new Result(
                selected.descriptor,
                KeyboardLayoutPolicy.compactCode(layouts, selectedIndex),
                selected.label,
                physicalKeyboards.size(),
                layouts.size(),
                imeState.imeId);
    }

    static HardwareKeyboardLayouts snapshot() throws ReflectiveOperationException {
        final List<InputDevice> keyboards = getExternalAlphabeticKeyboards();
        if (keyboards.isEmpty()) return new HardwareKeyboardLayouts(0, List.of());
        final FrameworkKeyboardLayoutApi manager = new FrameworkKeyboardLayoutApi();
        final ImeState ime = getImeState();
        final List<LayoutInfo> layouts = resolveConfiguredLayouts(manager, keyboards.get(0), ime);
        layouts.sort(Comparator.comparing(layout -> layout.descriptor));
        return new HardwareKeyboardLayouts(keyboards.size(), layouts.stream().map(layout ->
                new HardwareKeyboardLayouts.Choice(layout.descriptor, layout.label,
                        layout.subtype.equals(ime.currentSubtype))).toList());
    }

    private static List<LayoutInfo> resolveConfiguredLayouts(
            final FrameworkKeyboardLayoutApi inputManager,
            final InputDevice keyboard,
            final ImeState imeState) throws ReflectiveOperationException {
        final List<LayoutInfo> layouts = new ArrayList<>();
        final Set<String> seenDescriptors = new LinkedHashSet<>();
        for (final ImeSubtypeState mapping : imeState.layoutMappings) {
            final List<ResolvedLayout> resolvedLayouts =
                    inputManager.layouts(keyboard, 0, mapping.inputMethod, mapping.subtype);
            ResolvedLayout resolved = findBestLayout(
                    resolvedLayouts,
                    localeOf(mapping.subtype),
                    mapping.subtype
                            .getPhysicalKeyboardHintLayoutType());
            if (resolved == null) {
                final String configuredDescriptor =
                        inputManager.selectedDescriptor(
                                keyboard, 0,
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
                || !device.isExternal()) {
            return false;
        }
        final boolean hasKeyboardSource =
                (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return hasKeyboardSource
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static ImeState getImeState() throws ReflectiveOperationException {
        final FrameworkInputMethodCatalogApi inputMethods = new FrameworkInputMethodCatalogApi();
        final int userId = 0;
        final InputMethodInfo inputMethod = inputMethods.currentInputMethod(userId);
        final InputMethodSubtype currentSubtype = inputMethods.currentSubtype(userId);
        if (inputMethod == null || currentSubtype == null) {
            throw new IllegalStateException("current input method or subtype is unavailable");
        }

        final List<ImeSubtypeState> layoutMappings = new ArrayList<>();
        final Set<String> seenMappings = new LinkedHashSet<>();
        addSubtypeMapping(
                layoutMappings, seenMappings, inputMethod, currentSubtype);
        final List<InputMethodInfo> enabledInputMethods =
                inputMethods.enabled(userId);
        for (final InputMethodInfo enabledInputMethod : enabledInputMethods) {
            final List<InputMethodSubtype> enabledSubtypes =
                    inputMethods.enabledSubtypes(enabledInputMethod.getId(), userId);
            for (final InputMethodSubtype subtype : enabledSubtypes) {
                addSubtypeMapping(
                        layoutMappings, seenMappings,
                        enabledInputMethod, subtype);
            }
        }
        return new ImeState(inputMethod, currentSubtype, layoutMappings);
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
        final int physicalDevices;
        final int layouts;
        final String imeId;

        static Result noExternalKeyboard() {
            return new Result(null, null, null, 0, 0, null);
        }

        Result(
                final String descriptor,
                final String code,
                final String name,
                final int physicalDevices,
                final int layouts,
                final String imeId) {
            this.descriptor = descriptor;
            this.code = code;
            this.name = name;
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
                    + "name64=" + Base64.encodeToString(
                            name.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP) + '\n'
                    + "physicalDevices=" + physicalDevices + '\n'
                    + "layouts=" + layouts + '\n'
                    + "ime=" + imeId + '\n';
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
