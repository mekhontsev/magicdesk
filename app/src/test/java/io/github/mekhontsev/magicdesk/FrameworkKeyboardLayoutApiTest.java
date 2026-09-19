package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkKeyboardLayoutApiTest {
    @Test public void preservesIdentityReadOnlyQueriesAndVerifiedWrites() throws Exception {
        RuntimeSourceFixture.verify("""
                public static class Identifier { }
                public static class InputDevice {
                    final Identifier id = new Identifier();
                    public Identifier getIdentifier() { return id; }
                }
                static class InputMethodInfo { }
                static class InputMethodSubtype { }
                static class LocaleList {
                    static LocaleList getEmptyLocaleList() { return new LocaleList(); }
                }
                public static class Layout {
                    public String getDescriptor() { return "en"; }
                    public String getLabel() { return "English"; }
                    public LocaleList getLocales() { return null; }
                    public String getLayoutType() { return "qwerty"; }
                }
                public static class Selection {
                    public String getLayoutDescriptor() { return selected; }
                }
                static final InputDevice keyboard = new InputDevice();
                static final InputMethodInfo ime = new InputMethodInfo();
                static final InputMethodSubtype subtype = new InputMethodSubtype();
                static final List<String> calls = new ArrayList<>();
                static String selected = "en";
                static boolean noCandidates, noSelection, denied, stalled;
                public static class Service {
                    void identity(Identifier id, int user, InputMethodInfo method, InputMethodSubtype sub) {
                        check(id == keyboard.id && user == 10 && method == ime && sub == subtype,
                                "keyboard/profile/IME identity changed");
                    }
                    public Layout[] getKeyboardLayoutListForInputDevice(Identifier id, int user,
                            InputMethodInfo method, InputMethodSubtype sub) {
                        identity(id, user, method, sub); calls.add("list");
                        return noCandidates ? null : new Layout[]{new Layout()};
                    }
                    public Selection getKeyboardLayoutForInputDevice(Identifier id, int user,
                            InputMethodInfo method, InputMethodSubtype sub) {
                        identity(id, user, method, sub); calls.add("read");
                        return noSelection ? null : new Selection();
                    }
                    public void setKeyboardLayoutOverrideForInputDevice(Identifier id, String value) {
                        check(id == keyboard.id && value.equals("ru"), "override target");
                        calls.add("override");
                        if (denied) throw new SecurityException("denied");
                    }
                    public void setKeyboardLayoutForInputDevice(Identifier id, int user,
                            InputMethodInfo method, InputMethodSubtype sub, String value) {
                        identity(id, user, method, sub); calls.add("set");
                        if (!stalled) selected = value;
                    }
                }
                final Object mService = new Service();
                final Class<?> mApi = Service.class, mIdentifierClass = Identifier.class, mLayoutClass = Layout.class;
                public static void verify() throws Exception {
                    Fixture api = new Fixture();
                    var layouts = api.layouts(keyboard, 10, ime, subtype);
                    check(layouts.size() == 1 && layouts.get(0).descriptor.equals("en")
                            && layouts.get(0).label.equals("English") && layouts.get(0).locales != null
                            && layouts.get(0).layoutType.equals("qwerty"), "layout decoding");
                    noCandidates = true;
                    check(api.layouts(keyboard, 10, ime, subtype).isEmpty(), "null candidates");
                    noSelection = true;
                    check(api.selectedDescriptor(keyboard, 10, ime, subtype) == null, "null selection");
                    noSelection = false; calls.clear();
                    api.apply(keyboard, 10, ime, subtype, "en");
                    check(calls.equals(List.of("read")), "current layout was rewritten");
                    calls.clear();
                    api.apply(keyboard, 10, ime, subtype, "ru");
                    check(calls.equals(List.of("read", "override", "set", "read")), "write order/verification");
                    selected = "en"; stalled = true; calls.clear();
                    try { api.apply(keyboard, 10, ime, subtype, "ru"); throw new AssertionError("false success"); }
                    catch (IllegalStateException expected) { }
                    check(calls.equals(List.of("read", "override", "set", "read")), "unexpected retry");
                    denied = true; calls.clear();
                    try { api.apply(keyboard, 10, ime, subtype, "ru"); throw new AssertionError("denied accepted"); }
                    catch (InvocationTargetException error) { check(error.getCause() instanceof SecurityException, "cause"); }
                    check(calls.equals(List.of("read", "override")), "continued after denied write");
                }
                """ + RuntimeSourceFixture.methods("FrameworkKeyboardLayoutApi",
                        "layouts", "selectedDescriptor", "apply", "identifier")
                + RuntimeSourceFixture.nestedClass("FrameworkKeyboardLayoutApi", "ResolvedLayout"));
    }
}
