package io.github.mekhontsev.magicdesk;

/** User preference is narrower than host admission, never an authority to start Desktop. */
final class HostedChildWindowPolicy {
    static boolean external(boolean enabled, int sdk, boolean managed, boolean individualApplication) {
        return enabled && sdk >= 35 && managed && individualApplication;
    }
    private HostedChildWindowPolicy() { }
}
