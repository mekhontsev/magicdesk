package io.github.mekhontsev.magicdesk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Startup roles are protocol declarations, never deductions from a title, size or elapsed time. */
final class HostedLaunchWindows {
    enum Role { APPLICATION, UNCLASSIFIED, SPLASH, DIALOG }
    record Window(long id, boolean mapped, Role role) { }
    record Offer(long id, boolean primary, boolean replacesTemporary) { }
    private final Set<Long> submitted = new HashSet<>();
    private boolean primary;

    List<Offer> update(List<Window> windows) {
        if (primary) return List.of();
        var offers = new java.util.ArrayList<Offer>();
        // Prefer an actual application when a catalog also contains startup windows.
        for (var window : windows) if (window.mapped() && isPrimary(window.role())) {
            primary = true;
            offers.add(new Offer(window.id(), true, submitted.contains(window.id())));
            return List.copyOf(offers);
        }
        for (var window : windows) if (window.mapped() && submitted.add(window.id())) {
            offers.add(new Offer(window.id(), false, false));
        }
        submitted.retainAll(windows.stream().filter(Window::mapped).map(Window::id).toList());
        return List.copyOf(offers);
    }

    static boolean isPrimary(Role role) { return role == Role.APPLICATION || role == Role.UNCLASSIFIED; }
}
