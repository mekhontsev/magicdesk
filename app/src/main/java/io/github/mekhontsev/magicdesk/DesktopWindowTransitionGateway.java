package io.github.mekhontsev.magicdesk;

/** Executes semantic window requests through the active task backend. */
interface DesktopWindowTransitionGateway {
    /** Returns false when the request could not be queued. */
    boolean submit(
            DesktopWindowTransitionRequest request,
            TaskRepository.ActionCallback callback);
}
