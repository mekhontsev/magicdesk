package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.Locale;

final class KeyboardLayoutPolicy {
    interface Layout {
        String descriptor();
        Locale locale();
    }

    private KeyboardLayoutPolicy() {
    }

    static int findCurrentIndex(
            final List<? extends Layout> layouts,
            final String current) {
        if (current == null || current.isEmpty() || "null".equals(current)) {
            return -1;
        }
        for (int index = 0; index < layouts.size(); index++) {
            if (current.equals(layouts.get(index).descriptor())) {
                return index;
            }
        }
        return -1;
    }

    static String compactCode(
            final List<? extends Layout> layouts,
            final int selectedIndex) {
        final Layout selected = layouts.get(selectedIndex);
        final String language = languageCode(selected.locale());
        int matchingLanguages = 0;
        for (final Layout layout : layouts) {
            if (language.equals(languageCode(layout.locale()))) {
                matchingLanguages++;
            }
        }
        if (matchingLanguages <= 1) {
            return language;
        }

        final String regionalCode = regionalCode(selected);
        int matchingRegions = 0;
        for (final Layout layout : layouts) {
            if (regionalCode.equals(regionalCode(layout))) {
                matchingRegions++;
            }
        }
        if (matchingRegions <= 1) {
            return regionalCode;
        }
        int variant = 0;
        for (int index = 0; index <= selectedIndex; index++) {
            if (regionalCode.equals(regionalCode(layouts.get(index)))) {
                variant++;
            }
        }
        return regionalCode + "-" + variant;
    }

    private static String regionalCode(final Layout layout) {
        final String language = languageCode(layout.locale());
        final String country = layout.locale() == null
                ? "" : layout.locale().getCountry().toUpperCase(Locale.ROOT);
        return country.isEmpty() ? language : language + "-" + country;
    }

    private static String languageCode(final Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return "??";
        }
        return locale.getLanguage().toUpperCase(Locale.ROOT);
    }
}
