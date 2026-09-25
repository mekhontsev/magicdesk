package io.github.mekhontsev.magicdesk;

/** External family presentation requires an existing managed application host. */
final class HostedChildWindowPolicy {
    static boolean external(int sdk, boolean managed, boolean individualApplication) {
        return sdk >= 35 && managed && individualApplication;
    }
    private HostedChildWindowPolicy() { }
}
