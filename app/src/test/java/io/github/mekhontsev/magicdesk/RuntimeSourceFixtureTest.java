package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeSourceFixtureTest {
    @Test
    public void extractedOverridesCompileWithEitherHostLineEnding() throws Exception {
        for (final String newline : new String[] {"\n", "\r\n"}) {
            final String source = "@Override" + newline
                    + "public int value() { return 42; }" + newline;
            final String method = RuntimeSourceFixture.standaloneMethod(source);
            assertEquals("public int value() { return 42; }\n", method);
            RuntimeSourceFixture.verify(method + """
                    public static void verify() {
                        check(new Fixture().value() == 42, "method body changed");
                    }
                    """);
        }
    }

    @Test
    public void compilationFailureIncludesCompilerDiagnostics() {
        final AssertionError failure = assertThrows(AssertionError.class,
                () -> RuntimeSourceFixture.verify("""
                        public static void verify() { missingFixtureMethod(); }
                        """));
        assertTrue(failure.getMessage().contains("missingFixtureMethod"));
    }
}
