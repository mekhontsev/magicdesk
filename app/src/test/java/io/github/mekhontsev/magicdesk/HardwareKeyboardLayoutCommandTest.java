package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import com.android.internal.inputmethod.InputMethodSubtypeSafeList;

import org.junit.Test;

import java.util.Collections;

public final class HardwareKeyboardLayoutCommandTest {
    @Test
    public void acceptsLegacySubtypeList() throws ReflectiveOperationException {
        assertTrue(HardwareKeyboardLayoutCommand
                .extractEnabledInputMethodSubtypes(Collections.emptyList())
                .isEmpty());
    }

    @Test
    public void extractsSafeSubtypeList() throws ReflectiveOperationException {
        final InputMethodSubtypeSafeList safeList =
                new InputMethodSubtypeSafeList(Collections.emptyList());

        assertTrue(HardwareKeyboardLayoutCommand
                .extractEnabledInputMethodSubtypes(safeList)
                .isEmpty());
    }

    @Test public void startupUsesCurrentRussianRatherThanTheFirstEnglishLayout() throws Exception {
        verify("""
                current = 1;
                Result result = execute("sync");
                check(result.descriptor.equals("ru"), "startup selected another language");
                check(result.code.equals("RU"), "startup label disagrees with Android");
                check(switches == 0, "startup switched the IME");
                check(applied.equals(List.of("ru", "ru")), "keyboards did not share the live selection");
                """);
    }

    @Test public void startupAlsoKeepsCurrentEnglish() throws Exception {
        verify("""
                Result result = execute("sync");
                check(result.descriptor.equals("en") && result.code.equals("EN"), "English was not preserved");
                check(switches == 0, "startup switched the IME");
                """);
    }

    @Test public void firstShortcutSwitchesLanguageEvenAcrossDuplicateRussianImes() throws Exception {
        verify("""
                current = 1;
                Result result = execute("next");
                check(result.code.equals("EN"), "first shortcut only resynchronized Russian");
                check(switches == 2, "did not skip the second Russian IME");
                check(applied.equals(List.of("en", "en")), "applied an intermediate language");
                """);
    }

    @Test public void englishShortcutStopsAtTheFirstDifferentLanguage() throws Exception {
        verify("""
                Result result = execute("next");
                check(result.code.equals("RU"), "shortcut skipped the target language");
                check(switches == 1, "shortcut performed another unnecessary switch");
                """);
    }

    @Test public void unresolvableSubtypeDoesNotGuessAnotherLanguage() throws Exception {
        verify("""
                current = 1;
                missingSubtype = true;
                try {
                    execute("sync");
                    throw new AssertionError("unresolved subtype was silently replaced");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("current input method subtype"), "wrong failure");
                }
                check(applied.isEmpty() && switches == 0, "unknown state changed Android input");
                """);
    }

    @Test public void missingKeyboardDoesNotReadOrSwitchTheIme() throws Exception {
        verify("""
                keyboards.clear();
                check(execute("sync").descriptor == null, "missing keyboard reported a layout");
                check(imeReads == 0 && switches == 0, "no-keyboard refresh touched the IME");
                """);
    }

    @Test public void aSystemSwitchThatDoesNotAdvanceRemainsBounded() throws Exception {
        verify("""
                current = 1;
                stalledSwitch = true;
                Result result = execute("next");
                check(switches == states.size(), "unbounded IME switching");
                check(result.code.equals("RU"), "published a language Android did not select");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        final String methods = RuntimeSourceFixture.methods(
                "HardwareKeyboardLayoutCommand", "execute", "findSubtypeIndex")
                .replace("\"android.hardware.input.IInputManager\"",
                        "\"io.github.mekhontsev.magicdesk.Fixture$InputApi\"")
                .replace("\"android.hardware.input.KeyboardLayout\"",
                        "\"io.github.mekhontsev.magicdesk.Fixture$LayoutInfo\"");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class InputDevice {}
                static class InputMethodSubtype {
                    final String descriptor;
                    InputMethodSubtype(String value) { descriptor = value; }
                }
                static class ImeState {
                    final String imeId;
                    final InputMethodSubtype currentSubtype;
                    final List<Integer> layoutMappings = List.of(0, 1, 2);
                    ImeState(String id, String descriptor) {
                        imeId = id; currentSubtype = new InputMethodSubtype(descriptor);
                    }
                }
                public static class InputApi {
                    public Object getKeyboardLayout(String descriptor) { return null; }
                }
                public static class LayoutInfo implements KeyboardLayoutPolicy.Layout {
                    final String descriptor, label, inputMethod;
                    final InputMethodSubtype subtype;
                    LayoutInfo(ImeState state) {
                        subtype = state.currentSubtype;
                        descriptor = label = subtype.descriptor;
                        inputMethod = state.imeId;
                    }
                    public String descriptor() { return descriptor; }
                    public Locale locale() { return Locale.forLanguageTag(descriptor); }
                }
                static class Result {
                    final String descriptor, code;
                    Result(String descriptor, String code, String name, int devices, int layouts, String ime) {
                        this.descriptor = descriptor; this.code = code;
                    }
                    static Result noExternalKeyboard() { return new Result(null,null,null,0,0,null); }
                }
                static List<InputDevice> keyboards = new ArrayList<>(List.of(new InputDevice(), new InputDevice()));
                static List<ImeState> states = List.of(new ImeState("ime-a", "en"),
                        new ImeState("ime-b", "ru"), new ImeState("ime-a", "ru"));
                static List<String> applied = new ArrayList<>();
                static int current, switches, imeReads;
                static boolean missingSubtype, stalledSwitch;
                static List<InputDevice> getExternalAlphabeticKeyboards() { return keyboards; }
                static Object getInputManagerService() { return new InputApi(); }
                static ImeState getImeState() { imeReads++; return states.get(current); }
                static void switchInputMethodSubtype() {
                    switches++;
                    if (!stalledSwitch) current = (current + 1) % states.size();
                }
                static List<LayoutInfo> resolveConfiguredLayouts(Object service, Class<?> api,
                        Method method, Class<?> type, InputDevice keyboard, ImeState state) {
                    Map<String, LayoutInfo> layouts = new LinkedHashMap<>();
                    if (!missingSubtype) layouts.put(state.currentSubtype.descriptor, new LayoutInfo(state));
                    for (ImeState enabled : states) {
                        if (!missingSubtype || enabled.currentSubtype.descriptor.equals("en"))
                            layouts.putIfAbsent(enabled.currentSubtype.descriptor, new LayoutInfo(enabled));
                    }
                    return new ArrayList<>(layouts.values());
                }
                static void setKeyboardLayout(Object service, Class<?> api,
                        InputDevice keyboard, String ime, LayoutInfo selected) {
                    check(selected.subtype == states.get(current).currentSubtype, "applied non-current subtype");
                    applied.add(selected.descriptor);
                }
                public static void verify() throws Exception {
                """ + scenario + "}\n" + methods, "KeyboardLayoutPolicy");
    }
}
