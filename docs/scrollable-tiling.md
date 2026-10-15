# Scrollable Tiling

Scrollable Tiling brings [PaperWM](https://github.com/paperwm/PaperWM)-inspired window management to MagicDesk, arranging applications in a horizontally scrollable workspace. Each window maintains its own width and fills the available height, while new windows appear beside the active one instead of shrinking existing windows to fit the screen.

Navigate between windows using keyboard shortcuts, the taskbar, or optional on-screen controls. The workspace automatically scrolls to reveal the selected window while preserving the arrangement of neighboring windows. Windows can be resized, reordered, maximized, minimized, or temporarily detached into floating mode.

The feature is optional and works independently within each Desktop workspace. Disabling it restores the original floating-window positions, allowing seamless switching between traditional desktop multitasking and scrolling tiling.

## Using Scrollable Tiling

Right-click or long-press an empty area of Desktop, open **Scrollable tiling**,
and choose **Enable for this desktop session**. The menu also provides column
navigation, width and floating-window actions. Use the keyboard shortcuts to
change column order.

Tiling is off by default and applies only to the selected Desktop workspace.
Other Desktop workspaces and independent applications are unaffected. Closing
Desktop ends the tiling session; the mode does not start automatically next
time. Managed Desktop requires Android 15 / API 35; standalone tools on
Android 14 remain available without it.

New managed freeform windows join beside the active column. They keep their
existing width, up to the usable viewport width, and fill the work area's
height with a small gap. Opening another window does not shrink existing
columns. Closing one closes the gap. Changing the display size or panel layout
rearranges the strip within the updated work area.

Selecting an off-screen or clipped window through the taskbar, Alt+Tab,
overview, a direct click or automation reveals enough of its column to show
it completely. A fully visible column stays where it is. Navigation stops
at either end of the strip; it does not wrap around. Minimized windows are
skipped without losing their position or preferred width.

## Keyboard Shortcuts

| Shortcut                              | Action                                                                   |
| ------------------------------------- | ------------------------------------------------------------------------ |
| `Super + Ctrl + P`                    | Enable or disable scrollable tiling                                      |
| `Super + Ctrl + Left / Right`         | Select the previous or next column                                       |
| `Super + Ctrl + Shift + Left / Right` | Move the selected column left or right                                   |
| `Super + Ctrl + R`                    | Cycle the selected column through 1/3, 1/2, 2/3 and full usable viewport width |
| `Super + Ctrl + F`                    | Float the selected window or return it to the strip                      |

`Super` is the Windows/Meta key. Existing shortcuts, including Alt+Tab, remain
available. Tiling-specific bindings take precedence only while the mode is
active on the selected input Desktop.

Maximize, including `Super + Up`, expands a tiled column across the work area,
leaving its outer gap. Restore, including `Super + Down`, returns to the
previous column width. Minimizing a maximized window preserves that state;
work-area changes adjust its full-width placement. Cycling width exits the
maximized state.

Native caption maximize expands the column to the usable work area with its
outer gap intact. Clicking the caption button again restores the previous
column width, even when Android continues to label the button Maximize.
Resizing a column, including an explicit bounds request, updates its preferred
width and exits maximization; tiling continues to determine its position and
height. Layout submissions do not count as user resizes or overwrite the saved
floating rectangle.

## Floating, Fullscreen And Restoration

Choose **Float** or press `Super + Ctrl + F` to exclude a window from the strip.
A floating window can be moved, resized, snapped or placed independently;
choose the same action to rejoin it while it is freeform. Tiled windows retain
native captions, but dragging a caption does not reorder columns. Use the
reorder shortcuts instead. A native move returns to the assigned column
position. A native side-snap adopts the requested width while retaining the
column's order and full available height.

Maximize fills the usable width while keeping the window in the strip. Restore
returns its previous width. True fullscreen is separate: the layout leaves a
fullscreen window alone until it returns to freeform mode.

Already-fullscreen windows, picture-in-picture, Desktop infrastructure and
cross-package transient activities do not join the strip. Only managed
freeform tasks on the owning Desktop display are eligible; the layout does
not adopt independent applications or move tasks between displays.

**Disable and restore floating bounds** restores the saved positions of surviving
managed windows, then switches back to floating layout. Floating exclusions keep
their positions. Minimized windows remain minimized, and task focus and ordering
are preserved. If the restore command fails, tiling remains enabled and the
failure is reported. Closing Desktop releases the layout state through the
ordinary workspace cleanup path, which retains the original floating bounds.

## Mouse Navigation And Window Highlight

Clicking the exposed portion of a partially off-screen neighboring column
focuses and reveals it without passing that first click to its contents.
Fully visible windows accept the first click normally, including when two
columns fit side by side. The focused window also accepts clicks normally.

**Settings > Scrollable tiling > Window navigation edge buttons** adds small
Previous and Next tabs at the left and right edges of the usable work area.
They use the active appearance palette and remain available when the taskbar
is auto-hidden. An unavailable direction is disabled. The buttons use the same
column selection as the keyboard without taking focus from the application.
Only their small visible areas receive pointer input. They disappear when
tiling ends or the option is disabled.

**Settings > Scrollable tiling > Keyboard shortcuts** also provides two optional
custom bindings. Each can select a neighboring column, reorder a column,
cycle its width or float it. Choose an action and record a key combination.
Assigning the same combination to another slot clears the previous binding.
Both slots are unassigned by default. Actions run once when the keys are
released; held-key repeats do not repeat them. Custom bindings accept routed
physical keyboard input, not virtual or injected keys.

A programmable mouse can send keyboard combinations from its side buttons.
For example, assign `F9` and `F10` in the mouse's own software and record them
as Previous and Next. MagicDesk does not intercept ordinary raw mouse
Back/Forward events globally; Android's accessibility key filter only admits
keyboard-source events.

**Active window border**, also off by default, outlines the focused tiled
window in the current theme's accent color. It follows confirmed focus and
position changes, disappears when the focus leaves a visible tiled window,
and does not intercept mouse or keyboard input.

Taskbar reveal is configured separately under **Settings > Desktop > Click
screen edge to reveal taskbar**. The optional click target works alongside
normal hover reveal, including while scrolling tiling is active. The initial
reveal click is consumed rather than passed through to the application.

## Android Windowing Behavior

Android requires a minimum portion of a freeform task to remain inside the
display. MagicDesk therefore keeps small 64 dp previews of overflow columns
at the strip edges. When a selected column is maximized, it fills the work
area and the other columns remain behind it, retaining their order and widths.
Selecting a regular column restores the previews. The selected window always
fits within the usable viewport.

Activation orders tiled columns from farthest to nearest, with the selected
column on top. This keeps distant edge previews behind their nearer neighbors
instead of letting the previous focus order cover a partially visible column.
The focus border is hidden during workspace transitions and for concealed
tasks, then redrawn from the resulting task observation.

The strip works with real Android task bounds. It does not create an infinite
surface, hide tasks, replace system captions or suppress Android's snap
preview. There is no drag-to-scroll, touchpad scrolling, compositor animation
or vertical stacking. Gestures inside an application continue to belong to
that application.

Application minimum sizes and device-specific window policies can constrain a
requested arrangement, especially on small displays. A successful placement
command does not guarantee that Android used the exact requested bounds. The
existing task observer supplies the resulting geometry; an unchanged constrained
position does not trigger repeated resize attempts.

## Implementation

`PaperWindowLayout` keeps column order, preferred widths and horizontal offset.
`DesktopPaperWindowController` implements the shared `WindowLayoutPolicy`
contract. It returns placements and a new policy snapshot; rejected or cancelled
commands leave the current policy unchanged.

`WindowLayoutSession` owns the current policy, deduplicates observations and
acknowledges successful policy continuations. Tiling input actions use the shared
`DesktopWindowAction` vocabulary; layout calculations use their own typed commands.

`DesktopWindowLayoutController` saves original floating bounds and submits the
placements through the shared geometry executor. `DesktopTaskController`
serializes layout commands with other workspace operations. Taskbar, Alt+Tab and
automation activation use its reveal-before-focus route. Chrome input surfaces
over clipped neighbors use that same route; other native clicks use the existing
focus event to bring the selected column into view.

The policy does not reparent tasks, add a task observer or introduce a privileged
transport. Fullscreen ownership and ordinary floating-window resizing remain in
their existing controllers. See [Window layout policy](window-layout.md) for the
shared contract.
