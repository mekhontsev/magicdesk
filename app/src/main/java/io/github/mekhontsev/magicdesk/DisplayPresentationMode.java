package io.github.mekhontsev.magicdesk;

/** Surface mechanism; output attachments prefer direct, ordinary viewers explicitly mirror. */
enum DisplayPresentationMode {
    DIRECT("direct"), MIRROR("mirror");

    final String id;
    DisplayPresentationMode(String id) { this.id = id; }

    static DisplayPresentationMode forSource(DesktopDisplayInfo source) {
        return source.owned && "virtual".equals(source.source) ? DIRECT : MIRROR;
    }
}
