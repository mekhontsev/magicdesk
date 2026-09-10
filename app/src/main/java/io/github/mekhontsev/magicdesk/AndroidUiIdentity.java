package io.github.mekhontsev.magicdesk;

/** Immutable identity evidence: Android may recycle an accessibility id in a virtualized list. */
record AndroidUiIdentity(String packageName, String className, String resourceId, String uniqueId,
        int windowId, boolean editable, String text, String description) {
    boolean matches(final AndroidUiIdentity current) {
        return packageName.equals(current.packageName) && className.equals(current.className)
                && resourceId.equals(current.resourceId) && uniqueId.equals(current.uniqueId)
                && windowId == current.windowId && editable == current.editable
                && (editable || text.equals(current.text) && description.equals(current.description));
    }
}
