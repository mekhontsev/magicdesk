package io.github.mekhontsev.magicdesk;

/** Executes semantic window requests through the active task backend. */
interface DesktopWindowTransitionGateway {
    /** Returns false when the caller should use its established fallback. */
    boolean submit(DesktopWindowTransitionRequest request);
}
