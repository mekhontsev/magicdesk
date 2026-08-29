package io.github.mekhontsev.magicdesk.platform.nubia;

/** Resolves where a RedMagic firmware stores one hardware setting group. */
enum RedmagicSettingsNamespace {
    SYSTEM("system"),
    GLOBAL("global");

    final String shellName;

    RedmagicSettingsNamespace(final String shellName) {
        this.shellName = shellName;
    }
}
