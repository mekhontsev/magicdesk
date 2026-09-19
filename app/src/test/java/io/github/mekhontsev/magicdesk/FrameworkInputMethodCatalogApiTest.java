package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import com.android.internal.inputmethod.InputMethodSubtypeSafeList;
import java.util.List;
import static org.junit.Assert.assertTrue;

public final class FrameworkInputMethodCatalogApiTest {
    @Test public void stateAndSwitchPrimitivesKeepExplicitUserAndDisplay() throws Exception {
        RuntimeSourceFixture.verify("""
                static class InputMethodInfo { }
                static class InputMethodSubtype { }
                static final InputMethodInfo ime = new InputMethodInfo();
                static final InputMethodSubtype subtype = new InputMethodSubtype();
                static int switches;
                public static class Service {
                    public InputMethodInfo getCurrentInputMethodInfoAsUser(int user) {
                        check(user == 10, "IME profile"); return ime;
                    }
                    public InputMethodSubtype getCurrentInputMethodSubtype(int user) {
                        check(user == 10, "subtype profile"); return subtype;
                    }
                    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String id,
                            boolean implicit, int user) {
                        check(id.equals("test-ime") && implicit && user == 10, "subtype query");
                        return List.of(subtype);
                    }
                    public void onImeSwitchButtonClickFromSystem(int display) {
                        check(display == 7, "switch display"); switches++;
                    }
                }
                final Class<?> mApi = Service.class;
                final Object mService = new Service();
                static final String INPUT_METHOD_SUBTYPE_SAFE_LIST =
                        "com.android.internal.inputmethod.InputMethodSubtypeSafeList";
                public static void verify() throws Exception {
                    Fixture api = new Fixture();
                    check(api.currentInputMethod(10) == ime && api.currentSubtype(10) == subtype, "state");
                    check(api.enabledSubtypes("test-ime", 10).equals(List.of(subtype)), "subtypes");
                    check(switches == 0, "query changed IME");
                    api.switchSubtype(7);
                    check(switches == 1, "switch count");
                }
                """ + RuntimeSourceFixture.methods("FrameworkInputMethodCatalogApi",
                        "currentInputMethod", "currentSubtype", "enabledSubtypes",
                        "extractEnabledInputMethodSubtypes", "switchSubtype"));
    }

    @Test public void acceptsLegacyAndSafeSubtypeLists() throws Exception {
        assertTrue(FrameworkInputMethodCatalogApi.extractEnabledInputMethodSubtypes(List.of()).isEmpty());
        assertTrue(FrameworkInputMethodCatalogApi.extractEnabledInputMethodSubtypes(
                new InputMethodSubtypeSafeList(List.of())).isEmpty());
        assertTrue(FrameworkInputMethodCatalogApi.extractEnabledInputMethodSubtypes(null).isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUnknownSubtypeRepresentation() throws Exception {
        FrameworkInputMethodCatalogApi.extractEnabledInputMethodSubtypes(new Object());
    }

    @Test public void listsLegacyAndSafeListContractsWithoutRetryingFailures() throws Exception {
        RuntimeSourceFixture.verify("""
                static class InputMethodInfo { }
                Class<?> mApi;
                Object mService;
                static final List<InputMethodInfo> items = List.of(new InputMethodInfo());
                public static class Legacy {
                    public List<InputMethodInfo> getEnabledInputMethodListLegacy(int user) {
                        check(user == 10, "profile preserved"); return items;
                    }
                }
                public static class SafeList {
                    public static List<InputMethodInfo> extractFrom(SafeList list) { return items; }
                }
                public static class Modern extends Legacy {
                    public SafeList getEnabledInputMethodList(int user) {
                        check(user == 10, "profile preserved"); return new SafeList();
                    }
                    @Override public List<InputMethodInfo> getEnabledInputMethodListLegacy(int user) {
                        throw new AssertionError("legacy must not be called");
                    }
                }
                public static class DirectList {
                    public List<InputMethodInfo> getEnabledInputMethodList(int user) { return items; }
                }
                public static class Denied extends Modern {
                    @Override public SafeList getEnabledInputMethodList(int user) {
                        throw new SecurityException("denied");
                    }
                }
                public static class Missing extends Modern {
                    @Override public SafeList getEnabledInputMethodList(int user) { return null; }
                }
                public static void verify() throws Exception {
                    for (Object service : new Object[]{new Legacy(), new Modern(), new DirectList()}) {
                        check(enabled(service.getClass(), service, 10) == items, "list decoded");
                    }
                    try { enabled(Denied.class, new Denied(), 10); throw new AssertionError("denied accepted"); }
                    catch (InvocationTargetException error) { check(error.getCause() instanceof SecurityException, "cause"); }
                    try { enabled(Missing.class, new Missing(), 10); throw new AssertionError("missing accepted"); }
                    catch (IllegalStateException expected) { }
                }
                """ + RuntimeSourceFixture.methods("FrameworkInputMethodCatalogApi", "enabled"));
    }
}
