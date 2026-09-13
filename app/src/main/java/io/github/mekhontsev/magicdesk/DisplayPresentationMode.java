package io.github.mekhontsev.magicdesk;

/** Source capability, independent of viewer placement and Desktop residency. */
enum DisplayPresentationMode {
    DIRECT("direct"), MIRROR("mirror");

    final String id;
    DisplayPresentationMode(String id) { this.id = id; }

    static DisplayPresentationMode forSource(DesktopDisplayInfo source) {
        return source.owned && "virtual".equals(source.source) ? DIRECT : MIRROR;
    }
}
