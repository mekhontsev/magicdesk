package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkInputMethodCatalogApiTest {
    @Test public void listsLegacyAndSafeListContractsWithoutRetryingFailures() throws Exception {
        RuntimeSourceFixture.verify("""
                static class InputMethodInfo { }
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
