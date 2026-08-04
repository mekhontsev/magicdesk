package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public final class KeyboardLayoutPolicyTest {
    @Test
    public void findsPersistedDescriptor() {
        final List<TestLayout> layouts = Arrays.asList(
                layout("us", Locale.US), layout("ru", new Locale("ru", "RU")));

        assertEquals(1, KeyboardLayoutPolicy.findCurrentIndex(layouts, "ru"));
        assertEquals(-1, KeyboardLayoutPolicy.findCurrentIndex(layouts, "missing"));
    }

    @Test
    public void compactCodeDisambiguatesRegionAndVariant() {
        final List<TestLayout> layouts = Arrays.asList(
                layout("us-a", Locale.US),
                layout("gb", Locale.UK),
                layout("us-b", Locale.US));

        assertEquals("EN-US-1", KeyboardLayoutPolicy.compactCode(layouts, 0));
        assertEquals("EN-GB", KeyboardLayoutPolicy.compactCode(layouts, 1));
        assertEquals("EN-US-2", KeyboardLayoutPolicy.compactCode(layouts, 2));
    }

    @Test
    public void unknownLocaleGetsStableCode() {
        assertEquals(
                "??",
                KeyboardLayoutPolicy.compactCode(
                        Arrays.asList(layout("unknown", null)), 0));
    }

    private static TestLayout layout(
            final String descriptor, final Locale locale) {
        return new TestLayout(descriptor, locale);
    }

    private static final class TestLayout implements KeyboardLayoutPolicy.Layout {
        private final String mDescriptor;
        private final Locale mLocale;

        TestLayout(final String descriptor, final Locale locale) {
            mDescriptor = descriptor;
            mLocale = locale;
        }

        @Override
        public String descriptor() {
            return mDescriptor;
        }

        @Override
        public Locale locale() {
            return mLocale;
        }
    }
}
