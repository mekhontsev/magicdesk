package io.github.mekhontsev.magicdesk;

/** Artwork identity includes its provider; equal names in different installations never alias. */
record ApplicationIconKey(Kind kind, String owner, String name, DesktopExecBackend backend) {
    enum Kind { TERMUX, GUEST }

    static ApplicationIconKey of(DesktopApplicationShortcut shortcut, String termuxPackage) {
        if (shortcut == null) return null;
        if (shortcut.icon.startsWith("guest:")) return guest(shortcut.icon, shortcut.execBackend);
        return shortcut.hasExecLaunch() && shortcut.execBackend == DesktopExecBackend.TERMUX
                && termuxPackage != null && !termuxPackage.isEmpty() && TermuxIconCommand.valid(shortcut.icon)
                ? new ApplicationIconKey(Kind.TERMUX, termuxPackage, shortcut.icon, DesktopExecBackend.TERMUX) : null;
    }

    static ApplicationIconKey guest(String reference) { return guest(reference, DesktopExecBackend.SHELL); }

    static ApplicationIconKey guest(String reference, DesktopExecBackend backend) {
        if (reference == null || !reference.startsWith("guest:") || reference.length() > 1200) return null;
        int separator = reference.indexOf(':', 6);
        if (separator < 0) return null;
        String owner = reference.substring(6, separator), name = reference.substring(separator + 1);
        try {
            if (!java.util.UUID.fromString(owner).toString().equals(owner)) return null;
        } catch (IllegalArgumentException error) { return null; }
        return !name.isEmpty() && !name.contains("/") && !name.contains(":") && TermuxIconCommand.valid(name)
                ? new ApplicationIconKey(Kind.GUEST, owner, name, backend) : null;
    }

    static String desktopId(String path) {
        int directory = path.indexOf("/applications/");
        if (!path.startsWith("/") || directory < 0 || path.indexOf('\0') >= 0 || path.contains("/../"))
            throw new IllegalArgumentException("Invalid guest application path");
        return path.substring(directory + 14).replace('/', '-');
    }

    String guestReference() { return "guest:" + owner + ":" + name; }
}
